package com.wiyuka.acceleratedrecoiling.natives.realtime.compat;

import com.wiyuka.acceleratedrecoiling.AcceleratedRecoiling;
import com.wiyuka.acceleratedrecoiling.mixin.LivingEntityDoPushInvoker;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.transformer.ClassInfo;
import org.spongepowered.asm.util.Annotations;

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
        try {
            var type = readClass(CollisionMethods.class);
            var references = new HashMap<String, List<MethodInsnNode>>();

            for (var method : type.methods) {
                if (!method.name.equals("pushable") && !method.name.equals("soft")) {
                    continue;
                }

                var calls = new ArrayList<MethodInsnNode>();
                for (var instruction : method.instructions) {
                    if (instruction instanceof MethodInsnNode call) {
                        calls.add(call);
                    }
                }
                if (method.name.equals("pushable")) {
                    calls.add(doPushReference());
                }
                references.put(method.name, List.copyOf(calls));
            }

            return Map.copyOf(references);
        } catch (IOException | RuntimeException | LinkageError e) {
            AcceleratedRecoiling.LOGGER.warn("Could not read collision method references; using vanilla collisions", e);
            return Map.of();
        }
    }

    private static ClassNode readClass(Class<?> type) throws IOException {
        String resource = "/" + Type.getInternalName(type) + ".class";
        try (var input = type.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Missing collision method references: " + resource);
            }

            var node = new ClassNode();
            new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }

    private static MethodInsnNode doPushReference() throws IOException {
        var invoker = readClass(LivingEntityDoPushInvoker.class);
        for (var method : invoker.methods) {
            if (!method.name.equals("ar$doPush")) {
                continue;
            }

            String target = Annotations.getValue(Annotations.getVisible(method, Invoker.class));
            if (target == null || target.isEmpty()) {
                break;
            }

            return new MethodInsnNode(Opcodes.INVOKEVIRTUAL, Type.getInternalName(LivingEntity.class),
                    target, method.desc, false);
        }

        throw new IOException("Missing doPush invoker target");
    }

    private static void pushable(LivingEntity living, Entity entity) {
        living.isPushable();
        living.onClimbable();
        living.isAlive();
        living.getHealth();
        living.isSleeping();
        living.push(entity);

        entity.getRootVehicle();
        entity.isPassenger();
        entity.getVehicle();
        entity.isVehicle();
        entity.getTeam();
        entity.isSpectator();
        entity.isAlwaysTicking();
        entity.getInBlockState();
        entity.push(0, 0, 0);
        entity.setDeltaMovement(entity.getDeltaMovement());
        entity.canCollideWith(entity);
        entity.isPassengerOfSameVehicle(entity);
    }

    private static void soft(Entity entity) {
        entity.canBeCollidedWith();
        entity.isSpectator();
    }
}
