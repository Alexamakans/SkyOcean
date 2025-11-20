package me.owdding.skyocean.data.profile

import com.mojang.logging.LogUtils
import com.mongodb.reactivestreams.client.MongoClients
import me.owdding.ktcodecs.FieldName
import me.owdding.ktcodecs.GenerateCodec
import me.owdding.skyocean.utils.storage.CloudStorage
import net.minecraft.core.BlockPos
import net.minecraft.world.item.ItemStack
import java.util.concurrent.CopyOnWriteArrayList

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.event.*
import java.util.concurrent.TimeUnit

object IslandChestStorage {
    private var logger = LogUtils.getLogger()

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

    private val storage = CloudStorage(
        mongo = mongo,
        databaseName = "skyocean",
        collectionName = "profile_chest_items",
        defaultData = { CopyOnWriteArrayList() },
    )

    fun getItems(): List<ChestItem> {
        logger.warn("IslandChestStorage getItems skyocean")
        return storage.get()
    }

    fun hasBlock(position: BlockPos) =
        storage.get().any { (_, _, pos) -> pos == position }

    fun removeBlock(position: BlockPos) {
        val list = storage.get()
        list.removeAll { (_, _, pos) -> pos == position }
        val filter = list.filter { (_, _, _, pos2) -> pos2 == position }
        list.removeAll(filter.toSet())
        list.addAll(filter.map { (itemStack, slot, pos) ->
            ChestItem(itemStack, slot, pos, null, System.currentTimeMillis())
        })
        storage.save()
    }

    fun addItem(item: ItemStack, slot: Int, pos1: BlockPos, pos2: BlockPos?) {
        storage.get().add(ChestItem(item, slot, pos1, pos2, System.currentTimeMillis()))
        storage.save()
    }

    fun clear() {
        this.storage.get()?.clear()
        save()
    }

    fun save() {
        storage.save()
    }
}

@GenerateCodec
data class ChestItem(
    @FieldName("item_stack") val itemStack: ItemStack,
    val slot: Int = 0,
    val pos: BlockPos,
    val pos2: BlockPos?,
    val lastAccessed: Long,
)
