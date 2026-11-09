package com.example.lockon;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * R -> toggle lock-on (ON hides the FPS overlay, OFF shows it again)
 * G -> switch target type (MOBS <-> PLAYERS)
 * Only targets entities in front of you (180 degree half-space).
 * Written for Fabric 1.21.x (Yarn mappings).
 */
public class LockOnClient implements ClientModInitializer {

    // New targets are only picked within this range
    private static final double MAX_RANGE = 3.0;
    // A locked target is kept until it gets farther than this
    private static final double STICK_RANGE = 6.0;
    // A locked target is dropped once you turn this many degrees away from it (180 = exactly behind you)
    private static final double UNSTICK_ANGLE = 150.0;

    // Smoothing: each tick, turn this fraction of the remaining angle (0.0-1.0, lower = smoother/slower)
    private static final float SMOOTHING = 0.3f;
    // Never turn more than this many degrees in one tick
    private static final float MAX_STEP = 20.0f;

    private static boolean enabled = false;
    private static boolean targetPlayers = false;
    private static Entity lockedTarget = null;

    private static final KeyBinding.Category CATEGORY =
            KeyBinding.Category.create(Identifier.of("fpslock", "main"));

    private static KeyBinding toggleKey;
    private static KeyBinding modeKey;

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.fpslock.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, CATEGORY));
        modeKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.fpslock.mode", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void onTick(MinecraftClient client) {
        if (client.player == null || client.world == null) return;

        while (toggleKey.wasPressed()) {
            enabled = !enabled;
            lockedTarget = null;
            setSodiumExtraFps(client, !enabled); // lock-on ON -> FPS hidden
        }
        while (modeKey.wasPressed()) {
            targetPlayers = !targetPlayers;
            lockedTarget = null;
        }

        if (!enabled) return;

        // Keep the current target until it dies, gets past STICK_RANGE, or you turn away from it
        if (lockedTarget != null && !stillValid(client, lockedTarget)) lockedTarget = null;
        if (lockedTarget == null) lockedTarget = findClosestTarget(client);
        if (lockedTarget != null) aimAt(client.player, lockedTarget);
    }

    private boolean stillValid(MinecraftClient client, Entity e) {
        PlayerEntity self = client.player;
        if (e.isRemoved()) return false;
        if (!(e instanceof LivingEntity living) || !living.isAlive()) return false;
        if (self.squaredDistanceTo(e) > STICK_RANGE * STICK_RANGE) return false;

        Vec3d toTarget = e.getBoundingBox().getCenter().subtract(self.getEyePos());
        if (toTarget.lengthSquared() < 1.0E-6) return false;
        return self.getRotationVec(1.0f).dotProduct(toTarget.normalize()) > Math.cos(Math.toRadians(UNSTICK_ANGLE));
    }

    private Entity findClosestTarget(MinecraftClient client) {
        PlayerEntity self = client.player;
        Vec3d eyes = self.getEyePos();
        Vec3d look = self.getRotationVec(1.0f);

        Entity best = null;
        double bestDist = MAX_RANGE * MAX_RANGE;

        for (Entity e : client.world.getEntities()) {
            if (e == self || !(e instanceof LivingEntity living) || !living.isAlive()) continue;

            boolean matches = targetPlayers ? (e instanceof PlayerEntity) : (e instanceof MobEntity);
            if (!matches) continue;
            if (!self.canSee(e)) continue;

            // Front 180 degrees only: target must be on the same side as where you're looking
            Vec3d toTarget = e.getBoundingBox().getCenter().subtract(eyes);
            if (toTarget.lengthSquared() < 1.0E-6) continue;
            if (look.dotProduct(toTarget.normalize()) <= 0.0) continue;

            double d = self.squaredDistanceTo(e);
            if (d < bestDist) {
                bestDist = d;
                best = e;
            }
        }
        return best;
    }

    private void aimAt(PlayerEntity player, Entity target) {
        Vec3d from = player.getEyePos();
        Vec3d to = target.getBoundingBox().getCenter();

        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
        float pitch = (float) -(MathHelper.atan2(dy, horizontal) * 180.0 / Math.PI);

        float yawDiff = MathHelper.wrapDegrees(yaw - player.getYaw());
        float pitchDiff = pitch - player.getPitch();

        float yawStep = MathHelper.clamp(yawDiff * SMOOTHING, -MAX_STEP, MAX_STEP);
        float pitchStep = MathHelper.clamp(pitchDiff * SMOOTHING, -MAX_STEP, MAX_STEP);

        // Close enough: finish the last bit exactly so it doesn't lag behind slow targets
        if (Math.abs(yawDiff) < 0.5f) yawStep = yawDiff;
        if (Math.abs(pitchDiff) < 0.5f) pitchStep = pitchDiff;

        player.setYaw(player.getYaw() + yawStep);
        player.setPitch(MathHelper.clamp(player.getPitch() + pitchStep, -90.0f, 90.0f));
    }

    /** Sets Sodium Extra's "Show FPS" option via reflection so the mod still loads without it. */
    private static void setSodiumExtraFps(MinecraftClient client, boolean show) {
        try {
            Class<?> modClass = Class.forName("me.flashyreese.mods.sodiumextra.client.SodiumExtraClientMod");
            Object options = modClass.getMethod("options").invoke(null);
            Object extra = readField(options, "extraSettings");
            Field showFps = findField(extra.getClass(), "showFps");
            showFps.setBoolean(extra, show);

            // Best effort: persist to disk so it matches after restart
            try {
                Method write = options.getClass().getMethod("writeChanges");
                write.invoke(options);
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
        }
    }

    private static Object readField(Object obj, String name) throws Exception {
        return findField(obj.getClass(), name).get(obj);
    }

    private static Field findField(Class<?> cls, String name) throws Exception {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name);
    }
}
