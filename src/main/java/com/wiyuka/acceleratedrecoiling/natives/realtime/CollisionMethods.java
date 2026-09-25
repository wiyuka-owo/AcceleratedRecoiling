package com.wiyuka.acceleratedrecoiling.natives.realtime;

import com.wiyuka.acceleratedrecoiling.AcceleratedRecoiling;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.extensions.IBlockExtension;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.spongepowered.asm.mixin.transformer.ClassInfo;

final class CollisionMethods {
    private static final Map<String, List<MethodInsnNode>> REFERENCES = loadReferences();

    private CollisionMethods() {
    }

    static boolean inherited(Class<?> type, String group) {
        var references = REFERENCES.get(group);
        if (references == null || references.isEmpty()) {
            return false;
        }

        try {
            var info = ClassInfo.forName(type.getName());
            if (info == null) {
                throw new IllegalStateException("Missing class metadata");
            }

            for (var reference : references) {
                var method = info.findMethodInHierarchy(reference, ClassInfo.SearchType.ALL_CLASSES);
                if (method == null || !method.getOwner().getName().equals(reference.owner)) {
                    return false;
                }
                if (reference.itf && !interfacesUnchanged(info, reference)) {
                    return false;
                }
            }

            return true;
        } catch (RuntimeException | LinkageError e) {
            AcceleratedRecoiling.LOGGER.warn("Could not check collision methods for {}; using vanilla collisions",
                    type.getName(), e);
            return false;
        }
    }

    private static boolean interfacesUnchanged(ClassInfo type, MethodInsnNode reference) {
        for (String name : type.getInterfaces()) {
            var implemented = ClassInfo.forName(name);
            if (implemented == null) {
                throw new IllegalStateException("Missing interface metadata: " + name);
            }
            if (!name.equals(reference.owner) && implemented.findMethod(reference) != null) {
                return false;
            }
            if (!interfacesUnchanged(implemented, reference)) {
                return false;
            }
        }

        if (type.getSuperName() == null) {
            return true;
        }

        var parent = type.getSuperClass();
        if (parent == null) {
            throw new IllegalStateException("Missing superclass metadata: " + type.getSuperName());
        }
        return interfacesUnchanged(parent, reference);
    }

    private static Map<String, List<MethodInsnNode>> loadReferences() {
        String resource = "/" + Type.getInternalName(CollisionMethods.class) + ".class";
        try (var input = CollisionMethods.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Missing collision method references: " + resource);
            }

            var type = new ClassNode();
            new ClassReader(input).accept(type, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            var references = new HashMap<String, List<MethodInsnNode>>();

            for (var method : type.methods) {
                if (!method.name.equals("pushable") && !method.name.equals("soft") && !method.name.equals("ladder")) {
                    continue;
                }

                var calls = new ArrayList<MethodInsnNode>();
                for (var instruction : method.instructions) {
                    if (instruction instanceof MethodInsnNode call) {
                        calls.add(call);
                    }
                }
                references.put(method.name, List.copyOf(calls));
            }

            return Map.copyOf(references);
        } catch (IOException | RuntimeException | LinkageError e) {
            AcceleratedRecoiling.LOGGER.warn("Could not read collision method references; using vanilla collisions", e);
            return Map.of();
        }
    }

    private static void pushable(LivingEntity living, Entity entity) {
        living.isPushable();
        living.onClimbable();
        living.isAlive();
        living.getHealth();
        living.isSleeping();
        living.doPush(entity);
        living.push(entity);

        entity.getRootVehicle();
        entity.isPassenger();
        entity.getVehicle();
        entity.isVehicle();
        entity.getTeam();
        entity.isSpectator();
        entity.getInBlockState();
        entity.push(0, 0, 0);
        entity.canCollideWith(entity);
        entity.isPassengerOfSameVehicle(entity);
    }

    private static void soft(Entity entity) {
        entity.canBeCollidedWith(entity);
        entity.isSpectator();
    }

    private static void ladder(IBlockExtension block, BlockState state, LevelReader level,
            BlockPos position, LivingEntity entity) {
        block.isLadder(state, level, position, entity);
    }
}
