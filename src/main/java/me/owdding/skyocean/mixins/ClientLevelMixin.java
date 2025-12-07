package me.owdding.skyocean.mixins;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import me.owdding.skyocean.features.misc.ChestTracker;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {

    @Inject(method = "setBlock", at = @At("HEAD"))
    private void onSetBlock(BlockPos pos, BlockState state, int flags, int recursionLeft, CallbackInfoReturnable<Boolean> cir) {
        ClientLevel world = (ClientLevel) (Object) this;
        BlockState oldState = world.getBlockState(pos);

        ChestTracker.INSTANCE.handleBlockChange(pos, oldState, state);
    }
}
