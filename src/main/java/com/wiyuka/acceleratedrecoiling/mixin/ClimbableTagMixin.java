package com.wiyuka.acceleratedrecoiling.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.wiyuka.acceleratedrecoiling.natives.realtime.RealtimeNative;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.wiyuka.acceleratedrecoiling.natives.realtime.BatchedRules;

@Mixin(Holder.Reference.class)
public abstract class ClimbableTagMixin<T> {
    @Inject(method = "bindTags", at = @At("RETURN"))
    private void ar$tagsChanged(CallbackInfo ci) {
        BatchedRules.invalidatePolicy();
    }

    @Shadow
    private Set<TagKey<T>> tags;

    @Unique
    private TagMembership<T> ar$climbableMembership;

    @WrapMethod(method = "is(Lnet/minecraft/tags/TagKey;)Z")
    private boolean ar$climbableTag(TagKey<T> tag, Operation<Boolean> original) {
        if (tag != BlockTags.CLIMBABLE || !RealtimeNative.isEnabled()) {
            return original.call(tag);
        }
        Set<TagKey<T>> current = tags;
        TagMembership<T> cached = ar$climbableMembership;
        if (current != null && cached != null && cached.tags() == current) {
            return cached.member();
        }
        boolean result = original.call(tag);
        if (current == tags) {
            ar$climbableMembership = new TagMembership<>(current, result);
        }
        return result;
    }

    @Unique
    private record TagMembership<T>(Set<TagKey<T>> tags, boolean member) {
    }
}
