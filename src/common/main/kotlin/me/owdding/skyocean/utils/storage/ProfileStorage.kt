package me.owdding.skyocean.utils.storage

import com.google.gson.JsonElement
import com.mojang.serialization.Codec
import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.model.Filters
import com.mongodb.client.model.ReplaceOptions
import com.mongodb.reactivestreams.client.MongoClient
import com.mongodb.reactivestreams.client.MongoClients
import com.mongodb.reactivestreams.client.MongoCollection
import com.mongodb.reactivestreams.client.MongoDatabase
import me.owdding.ktmodules.Module
import me.owdding.skyocean.SkyOcean
import me.owdding.skyocean.utils.Utils.readJson
import me.owdding.skyocean.utils.Utils.writeJson
import org.apache.commons.io.FileUtils
import org.bson.Document
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription as MongoSubscription
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription
import tech.thatgravyboat.skyblockapi.api.events.base.predicates.TimePassed
import tech.thatgravyboat.skyblockapi.api.events.profile.ProfileChangeEvent
import tech.thatgravyboat.skyblockapi.api.events.time.TickEvent
import tech.thatgravyboat.skyblockapi.helpers.McPlayer
import tech.thatgravyboat.skyblockapi.utils.json.Json.toDataOrThrow
import tech.thatgravyboat.skyblockapi.utils.json.Json.toJson
import tech.thatgravyboat.skyblockapi.utils.json.Json.toPrettyString
import tech.thatgravyboat.skyblockapi.utils.json.JsonObject
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.relativeTo

internal class ProfileStorage<T : Any>(
    private val version: Int = 0,
    private var defaultData: () -> T,
    val fileName: String,
    val codec: (Int) -> Codec<T>,
) {
    // Use Mongo only for this logical file; everything else uses filesystem
    private val chestFileName = "chests"

    // ---------- Mongo singleton: reactive driver + keep-alive ----------
    private object MongoSingleton {
        private const val MONGO_URI =
            "mongodb://themongoestuserest:bigStrongVerynice2938749827@84.216.27.251:27017/?authSource=admin"
        private const val DB_NAME = "skyocean"
        private const val COLL_NAME = "profile_storage"

        @Volatile private var initialized = false
        private var client: MongoClient? = null
        private var database: MongoDatabase? = null
        private var collection: MongoCollection<Document>? = null
        private var scheduler: ScheduledExecutorService? = null

        // tiny no-op subscriber for fire-and-forget operations
        private class NoopSubscriber<T> : Subscriber<T> {
            override fun onSubscribe(s: MongoSubscription) { s.request(Long.MAX_VALUE) }
            override fun onNext(t: T) {}
            override fun onError(t: Throwable) {}
            override fun onComplete() {}
        }

        @Synchronized
        private fun initIfNeeded() {
            if (initialized) return

            val settings = MongoClientSettings.builder()
                .applyConnectionString(ConnectionString(MONGO_URI))
                .applyToConnectionPoolSettings {
                    it.minSize(1)              // keep one connection warm
                    it.maxSize(8)
                    it.maxConnecting(2)
                    it.maintenanceFrequency(30, TimeUnit.SECONDS)
                }
                .applyToClusterSettings { it.serverSelectionTimeout(10, TimeUnit.SECONDS) }
                .applyToSocketSettings {
                    it.connectTimeout(5, TimeUnit.SECONDS)
                    it.readTimeout(15, TimeUnit.SECONDS)
                }
                .retryWrites(true)
                .build()

            val c = MongoClients.create(settings)
            val db = c.getDatabase(DB_NAME)
            val col = db.getCollection(COLL_NAME, Document::class.java) // no inline/reified

            client = c
            database = db
            collection = col
            initialized = true

            // Keep-alive ping (driver heartbeats anyway; this just keeps a pooled connection hot)
            scheduler = Executors.newSingleThreadScheduledExecutor {
                Thread(it, "skyocean-mongo-keepalive").apply { isDaemon = true }
            }.also {
                it.scheduleAtFixedRate({
                    try { database?.runCommand(Document("ping", 1))?.subscribe(NoopSubscriber<Document>()) }
                    catch (_: Exception) {}
                }, 0, 30, TimeUnit.SECONDS)
            }
        }

        fun col(): MongoCollection<Document> {
            if (!initialized) initIfNeeded()
            return collection!!
        }
    }

    // --- Gson <-> Java (Mongo-friendly) converters ---
    private fun gsonToJava(el: JsonElement): Any? = when {
        el.isJsonNull -> null
        el.isJsonPrimitive -> {
            val p = el.asJsonPrimitive
            when {
                p.isBoolean -> p.asBoolean
                p.isNumber  -> {
                    val s = p.asString
                    if (s.contains('.') || s.contains('e', true)) s.toDouble()
                    else try { s.toLong() } catch (_: NumberFormatException) { s.toDouble() }
                }
                else -> p.asString
            }
        }
        el.isJsonArray -> el.asJsonArray.map { gsonToJava(it) }
        else -> {
            val obj = el.asJsonObject
            val d = Document()
            for ((k, v) in obj.entrySet()) d[k] = gsonToJava(v)
            d
        }
    }

    private fun javaToGson(v: Any?): JsonElement = when (v) {
        null -> com.google.gson.JsonNull.INSTANCE
        is Document -> {
            val o = com.google.gson.JsonObject()
            for ((k, value) in v) o.add(k, javaToGson(value))
            o
        }
        is Map<*, *> -> {
            val o = com.google.gson.JsonObject()
            for ((k, value) in v) if (k is String) o.add(k, javaToGson(value))
            o
        }
        is List<*> -> {
            val a = com.google.gson.JsonArray()
            v.forEach { a.add(javaToGson(it)) }
            a
        }
        is String -> com.google.gson.JsonPrimitive(v)
        is Boolean -> com.google.gson.JsonPrimitive(v)
        is Number -> com.google.gson.JsonPrimitive(v)
        else -> com.google.gson.JsonPrimitive(v.toString())
    }

    // Backend switch: Mongo for chests.json, files for everything else
    private val backend: StorageBackend<T> =
        if (fileName == chestFileName) MongoBackend() else FileBackend()

    // ---------- Shared state ----------
    private lateinit var data: T
    private var lastProfile: String? = null
    private lateinit var lastPath: Path

    private fun encodeToJsonElement(value: T): JsonElement? =
        value.toJson(codec(version))

    private fun decodeFromJsonElement(ver: Int, element: JsonElement): T =
        element.toDataOrThrow(codec(ver))

    private fun saveNow() {
        if (!this::data.isInitialized) return
        backend.save(data)
    }

    // ---------- Public API ----------
    fun get(): T? {
        if (fileName != chestFileName && isCurrentlyActive()) return data
        saveNow()
        load()
        return if (this::data.isInitialized) data else null
    }

    fun set(new: T) {
        if (fileName != chestFileName && isCurrentlyActive()) {
            data = new
            return
        }
        saveNow()
        load()
        if (this::data.isInitialized) data = new
    }

    fun save() {
        requiresSave.add(this)
    }

    fun load() = backend.load()?.let { data = it } ?: run {
        data = defaultData()
        saveNow()
    }

    // ---------- File helpers ----------
    private fun prepareFilePath(): Path {
        lastProfile = currentProfile
        val profile = lastProfile ?: return defaultPath.resolve(McPlayer.uuid.toString())
        return defaultPath
            .resolve(McPlayer.uuid.toString())
            .resolve(profile)
            .resolve("$fileName.json")
    }

    private fun isCurrentlyActive() =
        lastProfile != null && hasProfile() && currentProfile == lastProfile

    // Reactive helpers -> CompletableFuture
    private fun <T> Publisher<T>.toFutureFirst(): CompletableFuture<T?> {
        val cf = CompletableFuture<T?>()
        this.subscribe(object : Subscriber<T> {
            private var sub: MongoSubscription? = null
            private var first: T? = null
            override fun onSubscribe(s: MongoSubscription) { sub = s; s.request(1) }
            override fun onNext(t: T) { first = t; sub?.cancel() }
            override fun onError(t: Throwable) { cf.completeExceptionally(t) }
            override fun onComplete() { cf.complete(first) }
        })
        return cf
    }

    private fun Publisher<*>.toFutureVoid(): CompletableFuture<Void> {
        val cf = CompletableFuture<Void>()
        this.subscribe(object : Subscriber<Any> {
            override fun onSubscribe(s: MongoSubscription) { s.request(Long.MAX_VALUE) }
            override fun onNext(t: Any) {}
            override fun onError(t: Throwable) { cf.completeExceptionally(t) }
            override fun onComplete() { cf.complete(null) }
        })
        return cf
    }

    // ---------- Backends ----------
    private interface StorageBackend<T : Any> {
        fun load(): T?
        fun save(value: T)
    }

    private inner class FileBackend : StorageBackend<T> {
        override fun load(): T? {
            if (!hasProfile()) return null

            lastPath = prepareFilePath()
            if (!lastPath.exists()) {
                lastPath.createParentDirectories()
                return null
            }

            return try {
                val readJson = lastPath.readJson<com.google.gson.JsonObject>()
                val ver = readJson.get("@skyocean:version").asInt
                val dataEl = readJson.get("@skyocean:data")
                decodeFromJsonElement(ver, dataEl)
            } catch (e: Exception) {
                SkyOcean.error("Failed to load ${lastPath.relativeTo(defaultPath)}.", e)
                null
            }
        }

        override fun save(value: T) {
            if (!hasProfile()) return
            if (!this@ProfileStorage::lastPath.isInitialized) {
                lastPath = prepareFilePath()
                lastPath.createParentDirectories()
            }
            SkyOcean.debug("Saving $lastPath")
            try {
                val v = version
                val dataEl = encodeToJsonElement(value)
                    ?: return SkyOcean.warn("Failed to encode $value to json")

                val json = JsonObject {
                    this["@skyocean:version"] = v
                    this["@skyocean:data"] = dataEl
                }
                lastPath.writeJson(json)
                FileUtils.write(lastPath.toFile(), json.toPrettyString(), Charsets.UTF_8)
                SkyOcean.debug("saved $lastPath")
            } catch (e: Exception) {
                SkyOcean.error("Failed to save $value to file")
                e.printStackTrace()
            }
        }
    }

    private inner class MongoBackend : StorageBackend<T> {
        // `_id` = fileName (no extra index needed)
        private val idFilter = Filters.eq("_id", this@ProfileStorage.fileName)

        fun loadAsync(): CompletableFuture<T?> {
            return MongoSingleton.col()
                .find(idFilter)
                .first()
                .toFutureFirst()
                .thenApply { doc ->
                    if (doc == null) return@thenApply null
                    val ver = doc.getInteger("@skyocean:version")
                    val raw = doc.get("@skyocean:data")
                    if (raw == null) null else decodeFromJsonElement(ver, javaToGson(raw))
                }
                .exceptionally { e ->
                    SkyOcean.error("Failed to load $fileName from Mongo (async).", e)
                    null
                }
        }

        override fun load(): T? {
            return try {
                loadAsync().get() // block only here for compatibility
            } catch (e: Exception) {
                SkyOcean.error("Failed to load $fileName from Mongo.", e)
                null
            }
        }

        fun saveAsync(value: T): CompletableFuture<out Void?>? {
            val dataEl = encodeToJsonElement(value)
                ?: return CompletableFuture.completedFuture(null).also {
                    SkyOcean.warn("Failed to encode $value to json")
                }

            val doc = Document("_id", fileName)
                .append("@skyocean:version", version)
                .append("@skyocean:data", gsonToJava(dataEl)) // handles object OR array

            return MongoSingleton.col()
                .replaceOne(idFilter, doc, ReplaceOptions().upsert(true))
                .toFutureVoid()
                .exceptionally { e ->
                    SkyOcean.error("Failed to save $fileName to MongoDB", e)
                    null
                }
        }

        override fun save(value: T) {
            // fire-and-forget; non-blocking
            saveAsync(value)
        }
    }

    // ---------- Module / ticking ----------
    @Module
    companion object {
        val requiresSave = mutableSetOf<ProfileStorage<*>>()
        var currentProfile: String? = null

        @Subscription
        fun onProfileSwitch(event: ProfileChangeEvent) {
            currentProfile = event.name
        }

        @Subscription(TickEvent::class)
        @TimePassed("5s")
        fun onTick() {
            val toSave = requiresSave.toTypedArray()
            requiresSave.clear()
            // Reactive driver is already async; just call saveNow()
            toSave.forEach { it.saveNow() }
        }

        inline val defaultPath: Path get() = DataStorage.defaultPath
        private fun hasProfile() = currentProfile != null
    }
}
