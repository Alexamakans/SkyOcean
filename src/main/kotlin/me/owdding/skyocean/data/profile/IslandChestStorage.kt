package me.owdding.skyocean.data.profile

import com.mojang.logging.LogUtils
import me.owdding.ktcodecs.FieldName
import me.owdding.ktcodecs.GenerateCodec
import me.owdding.skyocean.utils.storage.CloudStorage
import net.minecraft.core.BlockPos
import net.minecraft.world.item.ItemStack

object IslandChestStorage {
    private var logger = LogUtils.getLogger()


    val storage = CloudStorage

    fun getItemsFiltered(filter: (ChestItem) -> Boolean): List<ChestItem> {
        return storage.getFiltered(filter)
    }

    fun <R> getItemsMapped(transform: (ChestItem) -> R): List<R> {
        return storage.getMapped(transform)
    }

    fun hasBlock(position: BlockPos): Boolean {
        return storage.hasBlock(position)
    }

    fun removeBlock(position: BlockPos) {
        storage.removeBlock(position)
    }

    fun addItem(item: ItemStack, slot: Int, pos1: BlockPos, pos2: BlockPos?) {
        storage.add(ChestItem(item, slot, pos1, pos2, System.currentTimeMillis()))
    }

    fun clear() {
        storage.clear()
    }

    fun save() {
        storage.save()
    }
}

@GenerateCodec
data class ChestItem(
    @FieldName("item_stack") var itemStack: ItemStack,
    val slot: Int = 0,
    val pos: BlockPos,
    val pos2: BlockPos?,
    var lastAccessed: Long,
)
