package name.djsweet.thorium.benchmarks

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import name.djsweet.thorium.ShareableScalarListQueryableData
import name.djsweet.thorium.encodeJsonToQueryableData
import name.djsweet.thorium.maxSafeKeyValueSizeSync
import org.openjdk.jmh.annotations.*
import net.jqwik.api.*
import java.util.concurrent.TimeUnit

@State(Scope.Benchmark)
class JsonSpec {
    var jsonObject = JsonObject()
    var jsonString: String = ""
    var byteBudget = 0

    private val scalarsArbitraries = listOf(
        Arbitraries.just(null),
        Arbitraries.oneOf(listOf(Arbitraries.just(true), Arbitraries.just(false))),
        Arbitraries.doubles(),
        Arbitraries.integers(),
        Arbitraries.strings(),
    )
    private val scalarsAndArraysArbitraries = this.scalarsArbitraries.toMutableList()

    init {
        this.scalarsAndArraysArbitraries.add(
            Arbitraries.oneOf(this.scalarsArbitraries)
                .list().ofMaxSize(10)
                .map {
            JsonArray(it)
        })
    }

    fun jsonObjectWithRemainingRecursion(recursion: Int): Arbitrary<JsonObject> {
        val valuesArbitrary = if (recursion <= 0) {
            Arbitraries.oneOf(this.scalarsAndArraysArbitraries)
        } else {
            val choiceList = this.scalarsAndArraysArbitraries.toMutableList()
            choiceList.add(this.jsonObjectWithRemainingRecursion(recursion - 1))
            Arbitraries.oneOf(choiceList)
        }
        return Arbitraries.strings().list().ofMinSize(4).ofMaxSize(12).flatMap { keys ->
            valuesArbitrary.list().ofSize(keys.size).map { values ->
                val result = JsonObject()
                for (i in keys.indices) {
                    result.put(keys[i], values[i])
                }
                result
            }
        }
    }

    val jsonObjectArbitrary = this.jsonObjectWithRemainingRecursion(2)

    @Setup(Level.Iteration)
    fun setup() {
        this.byteBudget = maxSafeKeyValueSizeSync()

        this.jsonObject = this.jsonObjectArbitrary.sample()
        this.jsonString = this.jsonObject.encode()
    }
}

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations=5)
@Measurement(iterations=30)
class JsonToQueryableDataEncoderBenchmark {
    @Benchmark
    fun convertJsonToQueryableData(spec: JsonSpec): ShareableScalarListQueryableData {
        return encodeJsonToQueryableData(spec.jsonObject, spec.byteBudget, 128)
    }

    @Benchmark
    fun convertJsonToQueryableDataFullParsing(spec: JsonSpec): ShareableScalarListQueryableData {
        val decoded = JsonObject(spec.jsonString)
        return encodeJsonToQueryableData(decoded, spec.byteBudget, 128)
    }
}