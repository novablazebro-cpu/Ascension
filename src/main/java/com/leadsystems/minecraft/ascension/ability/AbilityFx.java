package com.leadsystems.minecraft.ascension.ability;

import com.leadsystems.minecraft.ascension.scheduler.MainThread;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Short particle animations for title abilities. Purely visual: nothing here touches gameplay
 * state. Every shape is capped in points per frame and in frames, so a cast can never flood the
 * client or the main thread, whatever radius a config sets.
 */
final class AbilityFx {

    static final int MAX_POINTS = 72;
    static final int MAX_FRAMES = 40;

    private final MainThread main;

    AbilityFx(MainThread main) {
        this.main = main;
    }

    static Particle.DustOptions dust(int rgb, float size) {
        return new Particle.DustOptions(Color.fromRGB(rgb), size);
    }

    /** A flat ring that grows from the caster's feet to {@code radius}, one frame every 2 ticks. */
    void shockwave(Location center, double radius, Particle particle, Object data, int frames) {
        Location origin = center.clone().add(0, 0.15, 0);
        int count = Math.max(1, Math.min(MAX_FRAMES, frames));
        for (int frame = 1; frame <= count; frame++) {
            double r = Math.max(0.5, radius * frame / count);
            main.later((frame - 1) * 2L + 1L, () -> points(origin, ringPoints(r, pointsFor(r)), particle, data));
        }
    }

    /** A straight beam drawn from {@code from} to {@code to} over {@code ticks} (0 draws it at once). */
    void beam(Location from, Location to, Particle particle, Object data, int ticks) {
        if (from.getWorld() == null || !from.getWorld().equals(to.getWorld())) return;
        List<Vector> path = linePoints(from.toVector(), to.toVector(), 0.35);
        int frames = Math.max(1, Math.min(MAX_FRAMES, ticks));
        int perFrame = (path.size() + frames - 1) / frames;
        for (int frame = 0; frame < frames; frame++) {
            List<Vector> slice = path.subList(Math.min(path.size(), frame * perFrame),
                    Math.min(path.size(), (frame + 1) * perFrame));
            if (slice.isEmpty()) break;
            List<Vector> copy = List.copyOf(slice);
            Runnable draw = () -> points(from.getWorld(), copy, particle, data);
            if (frame == 0) draw.run(); else main.later(frame, draw);
        }
    }

    /** A jagged lightning arc between two points. */
    void arc(Location from, Location to, Particle particle, Object data) {
        if (from.getWorld() == null || !from.getWorld().equals(to.getWorld())) return;
        points(from.getWorld(), arcPoints(from.toVector(), to.toVector(), 0.6), particle, data);
    }

    /** A double helix rising around a living entity for {@code ticks}, following it as it moves. */
    void helix(Entity entity, double radius, double height, Particle particle, Object data, int ticks) {
        int frames = Math.max(1, Math.min(MAX_FRAMES, ticks));
        for (int frame = 0; frame < frames; frame++) {
            double progress = (double) frame / frames;
            double angle = progress * Math.PI * 4;
            main.later(frame + 1L, () -> {
                if (!entity.isValid()) return;
                Location base = entity.getLocation();
                for (int strand = 0; strand < 2; strand++) {
                    double a = angle + strand * Math.PI;
                    spawn(base.getWorld(), particle, base.getX() + Math.cos(a) * radius,
                            base.getY() + progress * height, base.getZ() + Math.sin(a) * radius, data);
                }
            });
        }
    }

    /** Particles left behind a moving entity for {@code ticks} (dashes, rushes). */
    void trail(Entity entity, Particle particle, Object data, int ticks) {
        int frames = Math.max(1, Math.min(MAX_FRAMES, ticks));
        for (int frame = 1; frame <= frames; frame++) {
            main.later(frame, () -> {
                if (!entity.isValid()) return;
                Location at = entity.getLocation();
                for (int i = 0; i < 3; i++) {
                    spawn(at.getWorld(), particle, at.getX() + jitter(0.3), at.getY() + 0.2 + jitter(0.3),
                            at.getZ() + jitter(0.3), data);
                }
            });
        }
    }

    /** A burst of particles rising from the ground in a column (marks a found block or a target). */
    void column(Location base, double height, Particle particle, Object data) {
        List<Vector> column = linePoints(base.toVector(), base.toVector().add(new Vector(0, height, 0)), 0.25);
        points(base.getWorld(), column, particle, data);
    }

    // ---------------------------------------------------------------- geometry (pure, testable)

    static int pointsFor(double radius) {
        return Math.max(16, Math.min(MAX_POINTS, (int) Math.ceil(radius * 8)));
    }

    static List<Vector> ringPoints(double radius, int points) {
        int count = Math.max(1, Math.min(MAX_POINTS, points));
        List<Vector> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double angle = Math.PI * 2 * i / count;
            out.add(new Vector(Math.cos(angle) * radius, 0, Math.sin(angle) * radius));
        }
        return out;
    }

    /** Evenly spaced points from start to end, widened so a long line still uses at most MAX_POINTS. */
    static List<Vector> linePoints(Vector start, Vector end, double spacing) {
        Vector delta = end.clone().subtract(start);
        double length = delta.length();
        int steps = Math.max(1, Math.min(MAX_POINTS - 1, (int) Math.ceil(length / Math.max(0.05, spacing))));
        List<Vector> out = new ArrayList<>(steps + 1);
        for (int i = 0; i <= steps; i++) {
            out.add(start.clone().add(delta.clone().multiply((double) i / steps)));
        }
        return out;
    }

    static List<Vector> arcPoints(Vector start, Vector end, double jag) {
        List<Vector> line = linePoints(start, end, 0.3);
        List<Vector> out = new ArrayList<>(line.size());
        for (int i = 0; i < line.size(); i++) {
            boolean endpoint = i == 0 || i == line.size() - 1;
            out.add(endpoint ? line.get(i) : line.get(i).clone().add(new Vector(jitter(jag), jitter(jag), jitter(jag))));
        }
        return out;
    }

    private static double jitter(double amount) {
        return (ThreadLocalRandom.current().nextDouble() - 0.5) * amount;
    }

    private static void points(Location origin, List<Vector> offsets, Particle particle, Object data) {
        World world = origin.getWorld();
        for (Vector offset : offsets) {
            spawn(world, particle, origin.getX() + offset.getX(), origin.getY() + offset.getY(),
                    origin.getZ() + offset.getZ(), data);
        }
    }

    private static void points(World world, List<Vector> absolute, Particle particle, Object data) {
        for (Vector point : absolute) {
            spawn(world, particle, point.getX(), point.getY(), point.getZ(), data);
        }
    }

    private static void spawn(World world, Particle particle, double x, double y, double z, Object data) {
        if (world == null) return;
        if (data == null) {
            world.spawnParticle(particle, x, y, z, 1, 0, 0, 0, 0);
        } else {
            world.spawnParticle(particle, x, y, z, 1, 0, 0, 0, 0, data);
        }
    }
}
