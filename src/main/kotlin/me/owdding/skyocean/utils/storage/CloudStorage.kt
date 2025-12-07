package me.owdding.skyocean.utils.storage

import com.google.gson.JsonParser
import com.mojang.authlib.minecraft.client.MinecraftClient
import com.mojang.serialization.Codec
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.Filters
import com.mongodb.client.model.UpdateOptions
import com.mongodb.reactivestreams.client.MongoCollection
import me.owdding.ktmodules.Module
import me.owdding.skyocean.data.profile.ChestItem
import me.owdding.skyocean.generated.SkyOceanCodecs
import net.minecraft.core.BlockPos
import org.bson.Document
import org.bson.conversions.Bson
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription as EventSub
import tech.thatgravyboat.skyblockapi.api.events.base.predicates.TimePassed
import tech.thatgravyboat.skyblockapi.api.events.time.TickEvent
import tech.thatgravyboat.skyblockapi.utils.json.Json.toDataOrThrow
import tech.thatgravyboat.skyblockapi.utils.json.Json.toJson
import java.util.concurrent.CompletableFuture
import com.mojang.logging.LogUtils
import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.event.ConnectionCheckOutFailedEvent
import com.mongodb.event.ConnectionPoolListener
import com.mongodb.reactivestreams.client.MongoClients
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock
import net.minecraft.client.Minecraft
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import tech.thatgravyboat.skyblockapi.api.events.profile.ProfileChangeEvent
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import tech.thatgravyboat.skyblockapi.api.events.level.PacketReceivedEvent
import tech.thatgravyboat.skyblockapi.utils.extentions.tag


/**
 * Stand-alone cloud storage for ChestItem lists.
 *
 * - Non-blocking loads & saves (reactive Mongo driver).
 * - Per-item upserts/deletes keyed by (slot, pos, pos2).
 * - Updates only applied if incoming lastAccessed > stored lastAccessed.
 * - Returns cached data immediately; background refresh fills it in.
 */
@Module
object CloudStorage {
    private val itemCodec: Codec<ChestItem> = SkyOceanCodecs.ChestItemCodec.codec()
    fun buildMongo(uri: String) = MongoClients.create(
        MongoClientSettings.builder()
            .applyConnectionString(ConnectionString("$uri&retryWrites=false")) // or add ?retryWrites=false in URI
            .applyToClusterSettings { b ->
                b.serverSelectionTimeout(5, TimeUnit.SECONDS) // fail fast if server down
            }
            .applyToSocketSettings { b ->
                b.connectTimeout(5, TimeUnit.SECONDS)
                b.readTimeout(10, TimeUnit.SECONDS)
            }
            .applyToConnectionPoolSettings { b ->
                b.maxSize(20)                  // cap total connections
                b.maxConnecting(4)             // avoid spikes
                b.maxWaitTime(5, TimeUnit.SECONDS) // don't wait 2 minutes for a socket
                b.maxConnectionIdleTime(60, TimeUnit.SECONDS)
                b.addConnectionPoolListener(object : ConnectionPoolListener {
                    override fun connectionCheckOutFailed(event: ConnectionCheckOutFailedEvent) {
                        // shows when you’d otherwise hit that 127s stall
                        me.owdding.skyocean.SkyOcean.warn("Mongo checkout failed: ${event.reason}")
                    }
                })
            }
            .applyToServerSettings { b -> b.heartbeatFrequency(10, TimeUnit.SECONDS) }
            .build()
    )

    // Build/reuse your MongoClient somewhere central in your mod, then inject it here.
    private val mongoURI = System.getProperty("skyocean.mongo.uri") ?: System.getenv("SKYOCEAN_MONGO_URI") ?: throw IllegalStateException("Mongo URI not configured")
    private val mongo = buildMongo(mongoURI)

    private const val databaseName = "skyocean"
    private var defaultData = { ArrayList<ChestItem>() }
    var currentProfile: String? = null
    private val PROFILE_ID_REGEX =
        Regex("""Profile ID:\s*([0-9a-fA-F-]{36})""")

    @EventSub
    fun onChat(event: PacketReceivedEvent) {
        val packet = event.packet

        if (packet is ClientboundSystemChatPacket) {
            val msg = packet.content.string
            handleProfileIdMessage(msg)
        }
    }

    fun handleProfileIdMessage(msg: String) {
        val match = PROFILE_ID_REGEX.find(msg) ?: return
        val id = match.groupValues[1]
        lock.withLock {
            logger.info("Extracted Profile ID: $id")
            currentProfile = id
            data = defaultData()
            collection = mongo.getDatabase(databaseName).getCollection(currentProfile!!)
            ensureIndexes(collection!!)
        }
    }

    val lock: ReentrantLock = ReentrantLock()
    private var logger = LogUtils.getLogger()
    private val t = TimeSource.Monotonic
    private val invalidateAfter = 1.seconds
    private var lastFetch = t.markNow()

    // In-memory cache (can be stale; that's okay by design)
    private lateinit var data: ArrayList<ChestItem>

    private var collection: MongoCollection<Document>? = null

    // ---- Public API (mirrors enough of ProfileStorage to be drop-in) ---------

    fun get(): ArrayList<ChestItem> {
        lock.withLock {
            if (!this::data.isInitialized) {
                data = defaultData()
            }
            load()
            return data
        }
    }

    fun save() {
        requiresSave.add(this)
    }

    fun add(item: ChestItem) {
        lock.withLock {
            data.add(item)
            save()
        }
    }

    fun clear() {
        lock.withLock {
            data.clear()
            save()
        }
    }

    fun removeBlock(position: BlockPos) {
        lock.withLock {
            if (!this::data.isInitialized) {
                data = defaultData()
            }

            val now = System.currentTimeMillis()
            val newList = ArrayList<ChestItem>(data.size)

            for (item in data) {
                when {
                    // Remove items whose primary position matches
                    item.pos == position -> {
                        newList.add(item.copy(itemStack = ItemStack(Items.AIR), pos = position, lastAccessed = now))
                    }

                    // Convert items whose secondary position matches
                    item.pos2 == position -> {
                        newList.add(
                            item.copy(
                                pos2 = null,
                                lastAccessed = now
                            )
                        )
                    }

                    // Keep all others unchanged
                    else -> newList.add(item)
                }
            }

            data.clear()
            data.addAll(newList)

            save()
        }
    }

    // You can call manually, but normally triggered via autosave tick
    fun load() {
        lock.withLock {
            if (collection == null) {
                return
            }
            if (!this::data.isInitialized) data = defaultData()
            if (t.markNow() - lastFetch < invalidateAfter) {
                return
            }
            lastFetch = t.markNow()

            //logger.warn("CloudStorage.load() triggered")
            //val filter = Filters.and(
            //    Filters.ne("deleted", true),
            //    Filters.not(Filters.regex("dataJson", "\\\"item_stack\\\"\\s*:\\s*\\{\\s*\\}"))
            //)
            collection!!.find().subscribe(
                onNext = { doc ->
                    decodeDoc(doc)?.let { incoming ->
                        val key = keyOf(incoming)
                        val existingIndex = data.indexOfFirst { keyOf(it) == key }
                        val existing = if (existingIndex >= 0) data[existingIndex] else null
                        if (existing == null || incoming.lastAccessed > existing.lastAccessed) {
                            if (existingIndex >= 0) data[existingIndex] = incoming else data.add(incoming)
                        }
                    }
                },
                onError = { e -> logger.error("Mongo load() failed", e) },
                onComplete = {
                    // logger.debug("Cloud load complete: ${lastSnapshot.size} chest items")
                }
            )
        }
    }

    // ---- Internal: autosave + DB I/O ----------------------------------------

    private fun saveToSystem() {
        lock.withLock {
            if (collection == null) {
                return
            }
            if (!this::data.isInitialized) return

            // Per-item gated upserts (only if incoming.lastAccessed is newer)
            val opts = UpdateOptions().upsert(true)
            data.forEach { item ->
                val filter = keyFilter(item)
                val pipeline = gatedUpsertPipeline(item)
                collection!!.updateOne(filter, pipeline, opts).subscribe(
                    onError = { e -> logger.error("Upsert failed for ${prettyKey(item)}", e) }
                )
            }
        }
    }

    private fun ensureIndexes(coll: MongoCollection<Document>) {
        val idx = Indexes.compoundIndex(
            Indexes.ascending(
                "slot", "posX", "posY", "posZ", "pos2X", "pos2Y", "pos2Z"
            )
        )
        coll.createIndex(idx, IndexOptions().unique(true).background(true))
            .subscribe({},{},{ /* ok */ })
    }

    // Aggregation-pipeline update that only applies changes when incoming is newer
    private fun gatedUpsertPipeline(item: ChestItem): List<Bson> {
        val newer = Document($$"$gt", listOf(item.lastAccessed, Document($$"$ifNull", listOf($$"$lastAccessed", -1L))))
        val setPayload = Document(mapOf(
            "lastAccessed" to Document($$"$cond", listOf(newer, item.lastAccessed, $$"$lastAccessed")),
            "slot" to Document($$"$cond", listOf(newer, item.slot, $$"$slot")),
            "posX" to Document($$"$cond", listOf(newer, item.pos.x, $$"$posX")),
            "posY" to Document($$"$cond", listOf(newer, item.pos.y, $$"$posY")),
            "posZ" to Document($$"$cond", listOf(newer, item.pos.z, $$"$posZ")),
            "pos2X" to Document($$"$cond", listOf(newer, item.pos2?.x, $$"$pos2X")),
            "pos2Y" to Document($$"$cond", listOf(newer, item.pos2?.y, $$"$pos2Y")),
            "pos2Z" to Document($$"$cond", listOf(newer, item.pos2?.z, $$"$pos2Z")),
            "deleted" to Document($$"$cond", listOf(newer, false, $$"$deleted")),
            "dataJson" to Document($$"$cond", listOf(newer, encodeJson(item), $$"$dataJson")),
            // was `$currentDate: { writeAt: true }`
            "writeAt" to Document($$"$cond", listOf(newer, $$$"$$NOW", $$"$writeAt")),
        ))
        return listOf(Document($$"$set", setPayload))
    }

    private fun keyFilter(item: ChestItem): Bson = Filters.and(
        Filters.eq("slot", item.slot),
        Filters.eq("posX", item.pos.x),
        Filters.eq("posY", item.pos.y),
        Filters.eq("posZ", item.pos.z),
        if (item.pos2 == null) Filters.eq("pos2X", null) else Filters.eq("pos2X", item.pos2.x),
        if (item.pos2 == null) Filters.eq("pos2Y", null) else Filters.eq("pos2Y", item.pos2.y),
        if (item.pos2 == null) Filters.eq("pos2Z", null) else Filters.eq("pos2Z", item.pos2.z),
    )

    private fun encodeJson(item: ChestItem): String =
        item.toJson(itemCodec)?.toString() ?: "{}"

    private fun decodeDoc(doc: Document): ChestItem? {
        val json = doc.getString("dataJson") ?: return null
        val je = JsonParser.parseString(json)
        return je.toDataOrThrow(itemCodec)
    }

    private fun prettyKey(item: ChestItem): String =
        "slot=${item.slot} pos=${fmt(item.pos)} pos2=${item.pos2?.let { fmt(it) } ?: "null"}"

    private fun fmt(p: BlockPos) = "[${p.x},${p.y},${p.z}]"

    private fun keyOf(item: ChestItem): String = buildString {
        append(item.slot).append('|')
        append(item.pos.x).append(',').append(item.pos.y).append(',').append(item.pos.z).append('|')
        if (item.pos2 == null) append("null") else append(item.pos2.x).append(',').append(item.pos2.y).append(',').append(item.pos2.z)
    }

    // Minimal reactive subscribe helper (non-blocking)
    private fun <T> Publisher<T>.subscribe(
        onNext: (T) -> Unit = {},
        onError: (Throwable) -> Unit = { logger.error("Mongo error", it) },
        onComplete: () -> Unit = {}
    ) {
        this.subscribe(object : Subscriber<T> {
            override fun onSubscribe(s: Subscription) { s.request(Long.MAX_VALUE) }
            override fun onNext(t: T) = onNext.invoke(t)
            override fun onError(t: Throwable) = onError.invoke(t)
            override fun onComplete() = onComplete.invoke()
        })
    }

    // ---- Autosave + profile tracking (5s, async) ----------------------------

    val requiresSave = mutableSetOf<CloudStorage>()

    @EventSub(TickEvent::class)
    @TimePassed("5s")
    fun onTick() {
        val toSave = requiresSave.toTypedArray()
        requiresSave.clear()
        CompletableFuture.runAsync {
            toSave.forEach { it.saveToSystem() }
        }
    }
}
