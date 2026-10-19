package dev.codingchest.client.mixin;
import dev.codingchest.client.CodingChestClient;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Consume press and release before vanilla keys or interaction packets see them. */
@Mixin(Mouse.class)
abstract class MouseSelectionMixin {
    @Inject(method="onMouseButton",at=@At("HEAD"),cancellable=true)
    private void codingchest$select(long window,int button,int action,int modifiers,CallbackInfo ci){
        CodingChestClient c=CodingChestClient.INSTANCE;if(c!=null&&c.handleSelectionMouse(window,button,action))ci.cancel();
    }
}
