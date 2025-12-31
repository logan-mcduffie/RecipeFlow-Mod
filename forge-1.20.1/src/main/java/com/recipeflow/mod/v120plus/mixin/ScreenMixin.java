package com.recipeflow.mod.v120plus.mixin;

import com.recipeflow.mod.v120plus.util.ShiftKeyHelper;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin to allow simulating the Shift key being held.
 * Used by ItemMetadataExtractor to capture mod-specific shift tooltips.
 */
@Mixin(Screen.class)
public class ScreenMixin {

    @Inject(method = "hasShiftDown", at = @At("HEAD"), cancellable = true)
    private static void onHasShiftDown(CallbackInfoReturnable<Boolean> cir) {
        if (ShiftKeyHelper.FORCE_SHIFT_DOWN.get()) {
            cir.setReturnValue(true);
        }
    }
}
