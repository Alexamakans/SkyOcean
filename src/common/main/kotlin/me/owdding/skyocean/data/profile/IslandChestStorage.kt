package me.owdding.skyocean.data.profile

import com.mongodb.reactivestreams.client.MongoClients
import me.owdding.ktcodecs.FieldName
import me.owdding.ktcodecs.GenerateCodec
import me.owdding.skyocean.utils.storage.CloudStorage
import net.minecraft.core.BlockPos
import net.minecraft.world.item.ItemStack
import java.util.concurrent.CopyOnWriteArrayList

object IslandChestStorage {

    // Build/reuse your MongoClient somewhere central in your mod, then inject it here.
    private val mongoURI = System.getProperty("skyocean.mongo.uri") ?: System.getenv("SKYOCEAN_MONGO_URI") ?: throw IllegalStateException("Mongo URI not configured")
    private val mongo = MongoClients.create(mongoURI)

    private val storage = CloudStorage(
        mongo = mongo,
        databaseName = "skyocean",
        collectionName = "profile_chest_items",
        defaultData = { CopyOnWriteArrayList() },
    )

    fun getItems(): List<ChestItem> {
        return storage.get() ?: mutableListOf()
    }

    fun hasBlock(position: BlockPos) =
        storage.get()?.any { (_, _, pos) -> pos == position } == true

    fun removeBlock(position: BlockPos) {
        val list = storage.get() ?: return
        list.removeAll { (_, _, pos) -> pos == position }
        val filter = list.filter { (_, _, _, pos2) -> pos2 == position }
        list.removeAll(filter.toSet())
        list.addAll(filter.map { (itemStack, slot, pos) ->
            ChestItem(itemStack, slot, pos, null, System.currentTimeMillis())
        })
        storage.save()
    }

    fun addItem(item: ItemStack, slot: Int, pos1: BlockPos, pos2: BlockPos?) {
        storage.get()?.add(ChestItem(item, slot, pos1, pos2, System.currentTimeMillis()))
        storage.save()
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
