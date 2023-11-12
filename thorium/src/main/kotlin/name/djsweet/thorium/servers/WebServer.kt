package name.djsweet.thorium.servers

import io.micrometer.prometheus.PrometheusMeterRegistry
import io.netty.handler.codec.http.QueryStringDecoder
import io.vertx.core.*
import io.vertx.core.Future.join
import io.vertx.core.buffer.Buffer
import io.vertx.core.eventbus.Message
import io.vertx.core.eventbus.MessageConsumer
import io.vertx.core.http.HttpMethod
import io.vertx.core.http.HttpServer
import io.vertx.core.http.HttpServerOptions
import io.vertx.core.http.HttpServerRequest
import io.vertx.core.http.HttpServerResponse
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.core.json.jsonObjectOf
import io.vertx.kotlin.coroutines.await
import kotlinx.collections.immutable.PersistentMap
import name.djsweet.thorium.*
import java.lang.NumberFormatException
import java.net.URLDecoder
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import kotlin.math.absoluteValue

fun writeCommonHeaders(resp: HttpServerResponse): HttpServerResponse {
    return resp
        .putHeader("Cache-Control", "no-store")
        .putHeader("Connection", "keep-alive")
        .putHeader("Access-Control-Allow-Origin", "*")
}

const val jsonMimeType = "application/json; charset=utf-8"
fun jsonStatusCodeResponse(req: HttpServerRequest, code: Int): HttpServerResponse {
    return writeCommonHeaders(req.response())
        .putHeader("Content-Type", jsonMimeType)
        .putHeader("ce-specversion", "1.0")
        .putHeader("ce-datacontenttype", jsonMimeType)
        .putHeader("ce-time", wallNowAsString())
        .putHeader("ce-type", "name.djsweet.thorium.channel.send.response")
        .putHeader("ce-source", "//thorium") // FIXME: Is this the right kind of source?
        // We don't need a cryptographically random source for event IDs, we just need to make sure
        // we effectively never have an ID collision, while at the same time avoiding blocking as much
        // as we possibly can.
        //
        // java.util.ThreadLocalRandom uses the same linear congruential generator as java.util.Random,
        // both with a period of around 2^48 results. Even when considering that we expect duplicates
        // after 2^24 attempts, these duplicates are extremely unlikely to generate a true collision,
        // considering we use 120 bits to generate the event ID.
        .putHeader("ce-id", generateOutboundEventID(ThreadLocalRandom.current()))
        .setStatusCode(code)
}

const val serverSentPingTimeout = 30_000.toLong()

val clientSerial: ThreadLocal<Long> = ThreadLocal.withInitial { 0.toLong() }
fun getClientIDFromSerial(): String {
    val current = clientSerial.get()
    clientSerial.set(current + 1)
    return "${Thread.currentThread().id}-$current"
}

class QueryClientSSEVerticle(
    private val resp: HttpServerResponse,
    private val clientAddress: String,
    private val clientID: String,
    private val channel: String,
    private val serverAddress: String,
): AbstractVerticle() {
    private var timerID: Long? = null
    private var pingFuture: Future<Void> = Future.succeededFuture()
    private var messageHandler: MessageConsumer<Any>? = null

    private fun writeHeadersIfNecessary() {
        if (this.resp.headWritten()) {
            return
        }
        val connectPayload = jsonObjectOf(
            "timestamp" to wallNowAsString(),
            "clientID" to this.clientID
        ).encode()
        writeCommonHeaders(this.resp)
            .setChunked(true)
            .setStatusCode(200)
            .putHeader("Content-Type", "text/event-stream; charset=utf-8")
            .write("event: connect\ndata: $connectPayload\n\n")
    }

    private fun writePing(): Future<Void> {
        return this.resp.write("event: ping\ndata: {\"timestamp\":\"${wallNowAsString()}\"}\n\n")
    }

    private fun setupPingTimer() {
        val vertx = this.vertx

        val currentTimerIDBeforeComment = this.timerID
        if (currentTimerIDBeforeComment != null) {
            vertx.cancelTimer(currentTimerIDBeforeComment)
            this.timerID = null
        }

        this.pingFuture = this.pingFuture.eventually {

            // If we're hammering on setupPingTimer, we're buffering up a ton
            // of .onComplete handlers on the same comment future. This permits
            // a weird world where we can establish multiple timers, and spam
            // the output with a ton of pings. Cancelling the outstanding timer
            // in here, again, prevents this weird world.
            val currentTimerIDAfterComment = this.timerID
            if (currentTimerIDAfterComment != null) {
                vertx.cancelTimer(currentTimerIDAfterComment)
            }

            this.timerID = vertx.setTimer(serverSentPingTimeout) {
                this.pingFuture = this.pingFuture.eventually {
                    writePing()
                }.onComplete {
                    this.setupPingTimer()
                }
            }

            Future.succeededFuture<Void>()
        }
    }

    override fun start() {
        val vertx = this.vertx
        val resp = this.resp
        val eventBus = vertx.eventBus()
        this.resp.endHandler {
            eventBus.request<Any>(
                this.serverAddress,
                UnregisterQueryRequest(this.channel, this.clientID),
                localRequestOptions
            ).onComplete {
                vertx.undeploy(this.deploymentID())
            }
        }
        this.messageHandler = eventBus.consumer(this.clientAddress) { message ->
            val messageBody = message.body()
            if (resp.ended()) {
                // It's important that we keep replying to the query server even when we're not
                // actually sending the data; if we dropped this message without replying to it,
                // we'd deadlock the query server until Vertx times out the request. This is
                // particularly important around the query un-registration process: we might receive
                // multiple data reports before the un-registration actually happens, even after
                // we've requested it.
                if (messageBody is ReportData) {
                    message.reply("ended")
                }
                return@consumer
            }
            this.writeHeadersIfNecessary()
            this.setupPingTimer()
            if (messageBody is ReportData) {
                resp.write(messageBody.serverSentEventPayload.value).onComplete {
                    message.reply("handled")
                }
            }
            if (messageBody is UnregisterQueryRequest) {
                resp.end()
            }
        }
    }

    override fun stop() {
        val timerID = this.timerID
        if (timerID != null) {
            this.vertx.cancelTimer(timerID)
        }
        this.messageHandler?.unregister()
        super.stop()
    }
}

const val channelsPrefix = "/channels/"
const val metricsPrefix = "/metrics"
const val keyReferenceCountPrefix = "/metrics/keycounts/"
val baseInvalidMethodJson = jsonObjectOf("code" to "invalid-method")
val baseInvalidChannelJson = jsonObjectOf("code" to "invalid-channel")
val internalFailureJson = jsonObjectOf("code" to "internal-failure")

fun failRequest(req: HttpServerRequest): Future<Void> {
    return jsonStatusCodeResponse(req, 500).end(internalFailureJson.encode())
}

fun handleQuery(
    vertx: Vertx,
    config: GlobalConfig,
    counters: GlobalCounterContext,
    channel: String,
    req: HttpServerRequest
) {
    counters.incrementGlobalQueryCountByAndGet(1)
    val queryMap = QueryStringDecoder(req.query() ?: "", false).parameters()
    val clientID = getClientIDFromSerial()
    val returnAddress = addressForQueryClientAtOffset(clientID)

    // The initial proposed query server is randomized, to ensure that too many concurrent connections
    // don't overwhelm a single query server.
    val queryThreads = config.queryThreads
    val initialOffset = ThreadLocalRandom.current().nextInt().absoluteValue % queryThreads
    var bestOffset = 0
    var bestQueryCount = Int.MAX_VALUE

    for (i in 0 until queryThreads) {
        val inspect = (i + initialOffset) % queryThreads
        val queryCount = counters.getQueryCountForThread(inspect).toInt()
        if (queryCount < bestQueryCount) {
            bestOffset = inspect
            bestQueryCount = queryCount
        }
    }
    val serverAddress = addressForQueryServerQuery(config, bestOffset)
    val response = req.response()
    val sseClient = QueryClientSSEVerticle(response, returnAddress, clientID, channel, serverAddress)
    val registerRequest = RegisterQueryRequest(
        channel,
        clientID,
        queryMap,
        returnAddress
    )
    val eventBus = vertx.eventBus()
    vertx.deployVerticle(sseClient).onComplete { deploymentIDResult ->
        if (deploymentIDResult.failed()) {
            failRequest(req)
            return@onComplete
        }
        val deploymentID = deploymentIDResult.result()
        response.endHandler {
            eventBus.request<Any>(
                serverAddress,
                UnregisterQueryRequest(channel, clientID),
                localRequestOptions
            ).onComplete {
                vertx.undeploy(deploymentID).onSuccess {
                    counters.decrementGlobalQueryCount(1)
                }
            }
        }
        eventBus.request<HttpProtocolErrorOrJson>(
            serverAddress,
            registerRequest,
            localRequestOptions
        ) {
            if (it.failed()) {
                failRequest(req)
            } else {
                it.result().body().whenError { err ->
                    jsonStatusCodeResponse(req, err.statusCode).end(err.contents.encode())
                }.whenSuccess {
                    // At this point, the entire request lifecycle is governed by the verticle we just registered.
                    // But we still need to make sure the headers get written, so we'll send an arbitrary string
                    // to trigger .writeHeadersIfNecessary.
                    eventBus.publish(returnAddress, "send-headers")
                }
            }
        }
    }
}

val missingContentTypeJson = jsonObjectOf("code" to "missing-content-type")
val baseUnsupportedContentTypeJson = jsonObjectOf("code" to "invalid-content-type")
val missingEventSourceJson = jsonObjectOf("code" to "missing-event-source")
val missingEventIDJson = jsonObjectOf("code" to "missing-event-id")
val invalidJsonBodyJson = jsonObjectOf("code" to "invalid-json-body")
val missingContentLengthJson = jsonObjectOf("code" to "missing-content-length")
val invalidContentLengthJson = jsonObjectOf("code" to "invalid-content-length")
val partialContentJson = jsonObjectOf("code" to "partial-content")
val bodyTooLargeJson = jsonObjectOf("code" to "body-too-large")
val bodyTimeoutJson = jsonObjectOf("code" to "body-timeout")
val baseExceededEventLimitJson = jsonObjectOf("code" to "exceeded-outstanding-event-limit")
val invalidDataFieldJson = jsonObjectOf("code" to "invalid-data-field")
val acceptedJson = jsonObjectOf("code" to "accepted")
// The X- prefix was deprecated in IETF RFC 6648, dated June 2012, so it's something of a free-for-all now.
const val bodyReadTimeHeader = "Thorium-Body-Read-Time"
const val jsonParseTimeHeader = "Thorium-JSON-Parse-Time"
const val eventEncodeTimeHeader = "Thorium-Encode-Time"
const val reportBatchSize = 256

fun handleDataWithUnpackRequest(
    vertx: Vertx,
    config: GlobalConfig,
    counters: GlobalCounterContext,
    unpackReqs: List<UnpackDataRequest>,
    httpReq: HttpServerRequest
) {
    val eventBus = vertx.eventBus()

    val queryThreads = config.queryThreads
    val translatorThreads = config.translatorThreads

    val eventIncrement = (unpackReqs.size * queryThreads).toLong()
    val newOutstandingEventCount = counters.incrementOutstandingEventCountByAndGet(eventIncrement)

    val priorEventCount = newOutstandingEventCount - eventIncrement
    val eventLimit = config.maxOutstandingEvents
    if (priorEventCount >= eventLimit) {
        counters.decrementOutstandingEventCount(eventIncrement)
        jsonStatusCodeResponse(httpReq, 429).end(
            baseExceededEventLimitJson.put("count", newOutstandingEventCount).put("limit", eventLimit).encode()
        )
        return
    }

    val translatorSendStartTime = monotonicNowMS()
    val requestLists = Array<MutableList<UnpackDataRequestWithIndex>>(translatorThreads) { mutableListOf() }
    for (i in unpackReqs.indices) {
        val unpackReq = unpackReqs[i]
        val targetTranslatorOffset = unpackReq.idempotencyKey.hashCode().absoluteValue % translatorThreads
        requestLists[targetTranslatorOffset].add(UnpackDataRequestWithIndex(i, unpackReq))
    }
    val translatorSends = requestLists.mapIndexed { index, unpackDataRequestsWithIndices ->
        val sendAddress = addressForTranslatorServer(config, index)
        eventBus.request<HttpProtocolErrorOrReportDataListWithIndexes>(
            sendAddress,
            UnpackDataRequestList(unpackDataRequestsWithIndices),
            localRequestOptions
        )
    }
    join(translatorSends).onFailure {
        counters.decrementOutstandingEventCount(eventIncrement)
        failRequest(httpReq)
    }.onSuccess translatorResults@ { futures ->
        val futuresSize = futures.size()
        val modBatchSize = unpackReqs.size % reportBatchSize
        val modBucket = ArrayList<ReportData?>(modBatchSize)
        val bucketsSize = unpackReqs.size / reportBatchSize
        val indexAtIntoModBucket = bucketsSize * reportBatchSize
        val buckets = Array<ArrayList<ReportData?>>(bucketsSize) { ArrayList(reportBatchSize) }

        for (i in 0 until futuresSize) {
            val responseList = futures.resultAt<Message<HttpProtocolErrorOrReportDataListWithIndexes>>(i).body()
            var hadError = false
            responseList.whenError { error ->
                counters.decrementOutstandingEventCount(eventIncrement)
                jsonStatusCodeResponse(httpReq, error.statusCode).end(error.contents.encode())
                hadError = true
            }.whenSuccess { reports ->
                for (maybeResponse in reports.responses) {
                    if (maybeResponse == null) {
                        continue
                    }
                    val (index, report) = maybeResponse
                    val bucket = if (index >= indexAtIntoModBucket) {
                        modBucket
                    } else {
                        buckets[index / reportBatchSize]
                    }
                    val indexInBucket = index % reportBatchSize
                    for (j in bucket.size..indexInBucket) {
                        bucket.add(null)
                    }
                    bucket[indexInBucket] = report
                }
            }
            if (hadError) return@translatorResults
        }
        for (i in 0 until bucketsSize) {
            val reportBatch = ReportDataList(buckets[i])
            eventBus.publish(addressForQueryServerData, reportBatch, localRequestOptions)
        }
        if (modBatchSize > 0) {
            val reportBatch = ReportDataList(modBucket)
            eventBus.publish(addressForQueryServerData, reportBatch, localRequestOptions)
        }

        jsonStatusCodeResponse(httpReq, 202)
            .putHeader(eventEncodeTimeHeader, "${monotonicNowMS() - translatorSendStartTime} ms")
            .end(acceptedJson.encode())
    }
}

fun readFullBodyBytes(
    vertx: Vertx,
    config: GlobalConfig,
    req: HttpServerRequest,
    handle: (resp: HttpServerResponse, bs: Buffer) -> Unit
) {
    val resp = req.response()
    val contentLengthString = req.headers()["content-length"]
    // Note that the cases below need to close the underlying HTTP connection instead of just returning normally.
    // In an HTTP/1.1 pipelined request model, we'll be waiting around to read the whole body before recovering,
    // and incurring ingress bandwidth as a result, _unless_ we close the underlying connection.
    if (contentLengthString == null) {
        jsonStatusCodeResponse(req, 400)
            .end(missingContentLengthJson.encode())
        req.connection().close()
        return
    }
    var contentLength = 0
    try {
        contentLength = Integer.parseInt(contentLengthString)
    } catch (e: NumberFormatException) {
        // Do nothing, we're going to handle this in the check below
    }
    if (contentLength <= 0) {
        jsonStatusCodeResponse(req, 400)
            .end(invalidContentLengthJson.encode())
        req.connection().close()
        return
    }
    if (contentLength > config.maxBodySizeBytes) {
        jsonStatusCodeResponse(req, 413)
            .end(bodyTooLargeJson.copy().put("maxBytes", config.maxBodySizeBytes).encode())
        req.connection().close()
        return
    }
    var bytesToGo = contentLength
    val buffer = Buffer.buffer(contentLength)
    val bodyReadStartTime = monotonicNowMS()
    val timeoutHandle = vertx.setTimer(config.bodyTimeoutMS.toLong()) {
        if (bytesToGo > 0) {
            jsonStatusCodeResponse(req, 408).end(bodyTimeoutJson.encode())
        }
    }
    req.handler { chunk ->
        if (chunk.length() > bytesToGo) {
            req.connection().close()
        }
        buffer.appendBuffer(chunk)
        bytesToGo -= chunk.length()
    }
    req.endHandler {
        vertx.cancelTimer(timeoutHandle)
        if (resp.closed()) {
            return@endHandler
        }
        resp.putHeader(bodyReadTimeHeader, "${monotonicNowMS() - bodyReadStartTime} ms")
        if (buffer.length() != contentLength) {
            jsonStatusCodeResponse(req, 400).end(partialContentJson.encode())
            // We somehow under-read the buffer. This shouldn't have happened, the connection isn't
            // going to work now.
            req.connection().close()
            return@endHandler
        }
        handle(resp, buffer)
    }
}

fun readBodyTryParseJsonObject(
    vertx: Vertx,
    config: GlobalConfig,
    req: HttpServerRequest,
    handle: (obj: JsonObject) -> Unit
) {
    readFullBodyBytes(vertx, config, req) { resp, bodyBytes ->
        val jsonParseStartTime = monotonicNowMS()

        // Clients are expected to send very large bodies. We've measured 440 kB payloads taking up to 5 ms,
        // which is enough to become very uncomfortable on the event loop, so all JSON parsing happens in
        // the worker pool.
        vertx.executeBlocking { ->
            JsonObject(bodyBytes)
        }.onFailure {
            jsonStatusCodeResponse(req, 400)
                .putHeader(jsonParseTimeHeader, "${monotonicNowMS() - jsonParseStartTime} ms")
                .end(invalidJsonBodyJson.encode())
        }.onSuccess {
            resp.putHeader(jsonParseTimeHeader, "${monotonicNowMS() - jsonParseStartTime} ms")
            handle(it)
        }
    }
}

fun readBodyTryParseJsonArray(
    vertx: Vertx,
    config: GlobalConfig,
    req: HttpServerRequest,
    handle: (arr: JsonArray, jsonParseStartTime: Long) -> Unit
) {
    readFullBodyBytes(vertx, config, req) { resp, bodyBytes ->
        val jsonParseStartTime = monotonicNowMS()

        // Clients are expected to send very large bodies. We've measured 440 kB payloads taking up to 5 ms,
        // which is enough to become very uncomfortable on the event loop, so all JSON parsing happens in
        // the worker pool.
        vertx.executeBlocking { ->
            JsonArray(bodyBytes)
        }.onFailure {
            jsonStatusCodeResponse(req,400)
                .putHeader(jsonParseTimeHeader, "${monotonicNowMS() - jsonParseStartTime} ms")
                .end(invalidJsonBodyJson.encode())
        }.onSuccess {
            resp.putHeader(jsonParseTimeHeader, "${monotonicNowMS() - jsonParseStartTime} ms")
            handle(it, jsonParseStartTime)
        }
    }
}

fun encodeEventSourceIDAsIdempotencyKey(source: String, id: String): String {
    return "$source $id"
}

fun handleData(
    vertx: Vertx,
    config: GlobalConfig,
    counters: GlobalCounterContext,
    channel: String,
    req: HttpServerRequest
) {
    val withParams = req.headers().get("Content-Type")
    if (withParams == null) {
        jsonStatusCodeResponse(req, 400).end(missingContentTypeJson.encode())
        return
    }
    val paramOffset = withParams.indexOf(";")
    when (if (paramOffset < 0) { withParams } else { withParams.substring(0, paramOffset) }) {
        "application/json" -> {
            val eventSource = req.headers().get("ce-source")
            if (eventSource == null) {
                jsonStatusCodeResponse(req, 400).end(missingEventSourceJson.encode())
                return
            }
            val eventID = req.headers().get("ce-id")
            if (eventID == null) {
                jsonStatusCodeResponse(req, 400).end(missingEventIDJson.encode())
                return
            }
            val idempotencyKey = encodeEventSourceIDAsIdempotencyKey(eventSource, eventID)
            readBodyTryParseJsonObject(vertx, config, req) { data ->
                val unpackRequest = UnpackDataRequest(
                    channel,
                    idempotencyKey,
                    data
                )
                handleDataWithUnpackRequest(
                    vertx,
                    config,
                    counters,
                    listOf(unpackRequest),
                    req
                )
            }
        }
        "application/cloudevents+json" -> {
            readBodyTryParseJsonObject(vertx, config, req) { json ->
                val eventSource = json.getValue("source")
                if (eventSource !is String) {
                    jsonStatusCodeResponse(req, 400).end(missingEventSourceJson.encode())
                    return@readBodyTryParseJsonObject
                }

                val eventID = json.getValue("id")
                if (eventID !is String) {
                    jsonStatusCodeResponse(req, 400).end(missingEventIDJson.encode())
                    return@readBodyTryParseJsonObject
                }

                val data = json.getValue("data")
                if (data !is JsonObject) {
                    jsonStatusCodeResponse(req, 400).end(invalidDataFieldJson.encode())
                    return@readBodyTryParseJsonObject
                }

                val idempotencyKey = encodeEventSourceIDAsIdempotencyKey(eventSource, eventID)
                val unpackRequest = UnpackDataRequest(
                    channel,
                    idempotencyKey,
                    data
                )
                handleDataWithUnpackRequest(
                    vertx,
                    config,
                    counters,
                    listOf(unpackRequest),
                    req
                )
            }
        }
        "application/cloudevents-batch+json" -> {
            readBodyTryParseJsonArray(vertx, config, req) { json, jsonParseStartTime ->
                val unpackRequests = mutableListOf<UnpackDataRequest>()
                for (i in 0 until json.size()) {
                    val elem = json.getValue(i)
                    if (elem !is JsonObject) {
                        jsonStatusCodeResponse(req,400).end(
                            invalidJsonBodyJson.copy().put("offset", i).encode()
                        )
                        return@readBodyTryParseJsonArray
                    }

                    val eventSource = elem.getValue("source")
                    if (eventSource !is String) {
                        jsonStatusCodeResponse(req, 400).end(
                            missingEventSourceJson.copy().put("offset", i).encode()
                        )
                        return@readBodyTryParseJsonArray
                    }

                    val eventID = elem.getValue("id")
                    if (eventID !is String) {
                        jsonStatusCodeResponse(req, 400).end(
                            missingEventIDJson.copy().put("offset", i).encode()
                        )
                        return@readBodyTryParseJsonArray
                    }

                    val data = elem.getValue("data")
                    if (data !is JsonObject) {
                        jsonStatusCodeResponse(req, 400).end(
                            invalidDataFieldJson.copy().put("offset", i).encode()
                        )
                        return@readBodyTryParseJsonArray
                    }

                    val idempotencyKey = encodeEventSourceIDAsIdempotencyKey(eventSource, eventID)
                    unpackRequests.add(UnpackDataRequest(
                        channel,
                        idempotencyKey,
                        data
                    ))
                }

                req.response().putHeader(jsonParseTimeHeader, "${monotonicNowMS() - jsonParseStartTime} ms")
                handleDataWithUnpackRequest(
                    vertx,
                    config,
                    counters,
                    unpackRequests,
                    req
                )

            }
        }
        else -> {
            jsonStatusCodeResponse(req, 400).end(
                baseUnsupportedContentTypeJson.put("content-type", withParams).encode()
            )
        }
    }
}

val notFoundJson = jsonObjectOf("code" to "not-found")

fun extractReferenceCountsToJSON(counts: PersistentMap<String, KeyPathReferenceCount>): JsonObject {
    val baseResult = jsonObjectOf()
    for ((key, value) in counts) {
        if (value.isEmpty()) {
            continue
        }
        if (value.subKeys.isEmpty()) {
            baseResult.put(key, value.references)
        } else {
            baseResult.put(key, extractReferenceCountsToJSON(value.subKeys))
        }
    }
    return baseResult
}

fun handleKeyReferenceCounts(counts: KeyPathReferenceCount, req: HttpServerRequest) {
    val result = extractReferenceCountsToJSON(counts.subKeys)
    jsonStatusCodeResponse(req,200).end(result.encode())
}

fun extractChannelFromRemainingPath(remainingPath: String): String? {
    val slashIndex = remainingPath.indexOf("/")
    if (slashIndex > 0 && slashIndex != remainingPath.length - 1) {
        return null
    }
    return URLDecoder.decode(if (slashIndex < 0) {
        remainingPath
    } else {
        remainingPath.substring(0, slashIndex)
    }, "UTF-8")
}

class WebServerVerticle(
    private val config: GlobalConfig,
    private val counters: GlobalCounterContext,
    private val meterRegistry: PrometheusMeterRegistry
): AbstractVerticle() {
    private var server: HttpServer? = null

    override fun start(promise: Promise<Void>) {
        super.start()

        val config = this.config
        val serverOptions = HttpServerOptions()
            .setPort(config.serverPort)
            .setCompressionSupported(false)
            .setDecompressionSupported(false)
            .setTcpKeepAlive(true)
            .setIdleTimeoutUnit(TimeUnit.MILLISECONDS)
            .setIdleTimeout(config.tcpIdleTimeoutMS)

        val server = this.vertx.createHttpServer(serverOptions)
        this.server = server

        server.requestHandler { req ->
            req.exceptionHandler {
                val resp = req.response()
                if (resp.ended()) return@exceptionHandler
                if (!resp.headWritten()) {
                    failRequest(req)
                } else {
                    resp.end()
                }
            }
            try {
                val path = req.path()
                if (path.startsWith(channelsPrefix)) {
                    val possiblyChannel = req.path().substring(channelsPrefix.length)
                    val channel = extractChannelFromRemainingPath(possiblyChannel)
                    if (channel == null) {
                        jsonStatusCodeResponse(req, 400).end(
                            baseInvalidChannelJson.copy().put("channel", possiblyChannel).encode()
                        )
                        return@requestHandler
                    }

                    if (req.method() == HttpMethod.POST || req.method() == HttpMethod.PUT) {
                        handleData(vertx, this.config, this.counters, channel, req)
                    } else if (req.method() == HttpMethod.GET) {
                        handleQuery(vertx, this.config, this.counters, channel, req)
                    } else {
                        jsonStatusCodeResponse(req, 400)
                            .end(baseInvalidMethodJson.copy().put("method", req.method()).encode())
                    }
                } else if (path.startsWith(metricsPrefix)) {
                    if (req.method() != HttpMethod.GET) {
                        jsonStatusCodeResponse(req, 400)
                            .end(baseInvalidChannelJson.copy().put("method", req.method()).encode())
                    }
                    if (path.length == metricsPrefix.length
                            || (path.length == metricsPrefix.length + 1 && path[metricsPrefix.length] == '/')) {
                        writeCommonHeaders(req.response())
                            .putHeader("Content-Type", "text/plain; version=0.0.4")
                            .end(this.meterRegistry.scrape())
                    } else if (path.startsWith(keyReferenceCountPrefix)) {
                        val possiblyChannel = req.path().substring(keyReferenceCountPrefix.length)
                        val channel = extractChannelFromRemainingPath(possiblyChannel)
                        if (channel == null) {
                            jsonStatusCodeResponse(req, 400).end(
                                baseInvalidChannelJson.copy().put("channel", possiblyChannel).encode()
                            )
                            return@requestHandler
                        }

                        val forChannel = counters.getKeyPathReferenceCountsForChannel(channel)
                        if (forChannel == null) {
                            jsonStatusCodeResponse(req, 404).end(notFoundJson.encode())
                            return@requestHandler
                        }

                        handleKeyReferenceCounts(forChannel, req)
                    } else {
                        jsonStatusCodeResponse(req, 404).end(notFoundJson.encode())
                    }
                } else {
                    jsonStatusCodeResponse(req, 404).end(notFoundJson.encode())
                }
            } catch (e: Exception) {
                val resp = req.response()
                if (resp.ended()) return@requestHandler
                if (!resp.headWritten()) {
                    failRequest(req)
                } else {
                    resp.end()
                }
            }
        }

        server.listen {
            if (it.failed()) {
                promise.fail(it.cause())
            } else {
                promise.complete()
            }
        }
    }

    override fun stop() {
        this.server?.close()
        super.stop()
    }
}

suspend fun registerWebServer(
    vertx: Vertx,
    config: GlobalConfig,
    counters: GlobalCounterContext,
    prom: PrometheusMeterRegistry,
): Set<String> {
    val deploymentIDs = mutableSetOf<String>()
    val webServerThreads = config.webServerThreads
    for (i in 0 until webServerThreads) {
        val deploymentID = vertx.deployVerticle(WebServerVerticle(config, counters, prom)).await()
        deploymentIDs.add(deploymentID)
    }
    return deploymentIDs
}