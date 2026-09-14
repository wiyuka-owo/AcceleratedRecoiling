package com.wiyuka.acceleratedrecoiling.mixin;

import com.wiyuka.acceleratedrecoiling.api.ICustomData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class EntityMixin implements ICustomData {
    private double bbMinX = 0.0;
    private double bbMinY = 0.0;
    private double bbMinZ = 0.0;
    private double bbMaxX = 0.0;
    private double bbMaxY = 0.0;
    private double bbMaxZ = 0.0;
    private float density = 0.0f;

    @Unique
    private int nativeId = -1;

    @Override
    public int getNativeId() {
        return nativeId;
    }

    @Override
    public void setNativeId(int nativeId) {
        this.nativeId = nativeId;
    }

    @Override
    public void setDensity(float density) {
        this.density = density;
    }

    @Override
    public float getDensity() {
        return density;
    }

    @Override
    public final void extractionBoundingBox(double[] doubleArray, int offset, double inflate) {
        doubleArray[offset + 0] = (double) this.bbMinX - inflate;
        doubleArray[offset + 1] = (double) this.bbMinY - inflate;
        doubleArray[offset + 2] = (double) this.bbMinZ - inflate;
        doubleArray[offset + 3] = (double) this.bbMaxX + inflate;
        doubleArray[offset + 4] = (double) this.bbMaxY + inflate;
        doubleArray[offset + 5] = (double) this.bbMaxZ + inflate;
    }

    @Shadow
    private Vec3 position;

    @Shadow
    public abstract boolean isRemoved();

    @Override
    public final void extractionPosition(double[] doubleArray, int offset) {
        doubleArray[offset + 0] = (double) this.position.x;
        doubleArray[offset + 1] = (double) this.position.y;
        doubleArray[offset + 2] = (double) this.position.z;
    }

    @Inject(
            method = "setBoundingBox(Lnet/minecraft/world/phys/AABB;)V",
            at = @At("RETURN")
    )
    private void onSetBoundingBox(AABB bb, CallbackInfo ci) {
        this.bbMinX = bb.minX;
        this.bbMinY = bb.minY;
        this.bbMinZ = bb.minZ;
    this.bbMaxX = bb.maxX;
    this.bbMaxY = bb.maxY;
    this.bbMaxZ = bb.maxZ;
}
}
