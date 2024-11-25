package kevlar.kevlarCarts;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class MinecartListener implements Listener {
    private final KevlarCarts plugin;
    private static final double FOLLOW_DISTANCE = 1.0;
    private static final double CATCH_UP_DISTANCE = 2.0;
    private static final double TELEPORT_DISTANCE = 5.0;
    private static final double STUCK_THRESHOLD = 0.01;
    private final Map<UUID, Location> lastLocations = new HashMap<>();
    private final Map<UUID, Integer> stuckTicks = new HashMap<>();
    private static final int STUCK_TICKS_THRESHOLD = 20; // 1 second

    public MinecartListener(KevlarCarts plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onCartMove(VehicleMoveEvent event) {
        if (!(event.getVehicle() instanceof Minecart)) {
            return;
        }

        Minecart cart = (Minecart) event.getVehicle();
        UUID cartId = cart.getUniqueId();

        // Get carts that should follow this cart
        Set<UUID> connectedCarts = plugin.getConnectedCarts().get(cartId);
        if (connectedCarts != null) {
            Location cartLoc = cart.getLocation();
            Vector velocity = cart.getVelocity();
            Vector direction = velocity.clone().normalize();

            // Only process if the cart is actually moving
            if (velocity.lengthSquared() > 0.01) {
                for (UUID connectedId : connectedCarts) {
                    Entity entity = Bukkit.getEntity(connectedId);
                    if (entity instanceof Minecart) {
                        Minecart follower = (Minecart) entity;
                        handleFollowerCart(cart, follower, velocity);
                    }
                }
            }
        }

        // Handle driver cart behavior
        if (plugin.getDriverCarts().containsKey(cartId)) {
            Set<UUID> followers = plugin.getDriverCarts().get(cartId);
            Location cartLoc = cart.getLocation();
            Vector velocity = cart.getVelocity();

            // Only process if the driver cart is moving
            if (velocity.lengthSquared() > 0.01) {
                for (UUID followerId : followers) {
                    Entity entity = Bukkit.getEntity(followerId);
                    if (entity instanceof Minecart) {
                        Minecart follower = (Minecart) entity;
                        handleFollowerCart(cart, follower, velocity);
                    }
                }
            }
        }
    }

    private void handleFollowerCart(Minecart leader, Minecart follower, Vector leaderVelocity) {
        Location leaderLoc = leader.getLocation();
        Location followerLoc = follower.getLocation();
        UUID followerId = follower.getUniqueId();

        // Skip if this cart is a driver cart (to prevent conflicts)
        if (plugin.getDriverCarts().containsKey(follower.getUniqueId())) {
            return;
        }

        double distance = leaderLoc.distance(followerLoc);

        // Check if cart is stuck
        Location lastLoc = lastLocations.get(followerId);
        if (lastLoc != null) {
            if (lastLoc.distance(followerLoc) < STUCK_THRESHOLD) {
                int stuck = stuckTicks.getOrDefault(followerId, 0) + 1;
                stuckTicks.put(followerId, stuck);

                if (stuck >= STUCK_TICKS_THRESHOLD) {
                    // Cart is stuck, teleport it
                    teleportCartBehindLeader(leader, follower);
                    stuckTicks.remove(followerId);
                    return;
                }
            } else {
                stuckTicks.remove(followerId);
            }
        }
        lastLocations.put(followerId, followerLoc);

        // Check if cart is too far behind
        if (distance > TELEPORT_DISTANCE) {
            teleportCartBehindLeader(leader, follower);
            return;
        }

        // Normal following behavior
        if (distance > FOLLOW_DISTANCE) {
            Vector adjustedVelocity = leaderVelocity.clone();

            if (distance > CATCH_UP_DISTANCE) {
                adjustedVelocity.multiply(1.15);
            }

            follower.setVelocity(adjustedVelocity);
        } else {
            follower.setVelocity(leaderVelocity);
        }
    }

    private void teleportCartBehindLeader(Minecart leader, Minecart follower) {
        Location leaderLoc = leader.getLocation();
        Vector direction = leader.getVelocity();

        // If not moving, use the leader's facing direction
        if (direction.lengthSquared() < 0.01) {
            float yaw = leaderLoc.getYaw();
            direction = new Vector(
                    -Math.sin(Math.toRadians(yaw)),
                    0,
                    Math.cos(Math.toRadians(yaw))
            );
        } else {
            direction = direction.normalize();
        }

        // Calculate position behind leader
        Vector offset = direction.multiply(-FOLLOW_DISTANCE);
        Location teleportLoc = leaderLoc.clone().add(offset);

        // Maintain the same Y-level as the leader to stay on tracks
        teleportLoc.setY(leaderLoc.getY());

        // Copy leader's rotation
        teleportLoc.setYaw(leaderLoc.getYaw());
        teleportLoc.setPitch(leaderLoc.getPitch());

        // Teleport and match velocity
        follower.teleport(teleportLoc);
        follower.setVelocity(leader.getVelocity());
    }
}