package kevlar.kevlarCarts;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.*;

public class KevlarCarts extends JavaPlugin {
    private final Map<UUID, Set<UUID>> connectedCarts = new HashMap<>();
    private final Map<UUID, Set<UUID>> driverCarts = new HashMap<>();
    private int connectionRadius;
    private int maxConnectedCarts;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfig();
        getServer().getPluginManager().registerEvents(new MinecartListener(this), this);
        getLogger().info("KevlarCarts has been enabled!");
    }

    @Override
    public void onDisable() {
        connectedCarts.clear();
        driverCarts.clear();
        getLogger().info("KevlarCarts has been disabled!");
    }

    private void loadConfig() {
        FileConfiguration config = getConfig();
        connectionRadius = config.getInt("connection-radius", 5);
        maxConnectedCarts = config.getInt("max-connected-carts", 10);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("kevlarcartsreload")) {
            if (!sender.hasPermission("kevlarcarts.reload")) {
                sender.sendMessage("§cYou don't have permission to use this command!");
                return true;
            }
            reloadConfig();
            loadConfig();
            sender.sendMessage("§aKevlarCarts configuration reloaded!");
            return true;
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage("§cThis command can only be used by players!");
            return true;
        }

        Player player = (Player) sender;

        switch (command.getName().toLowerCase()) {
            case "kevlarcartsconnect":
                return handleCartConnect(player);
            case "kevlarcartsdisconnect":
                return handleCartDisconnect(player);
            case "kevlarcartsdriver":
                return handleCartDriver(player);
            default:
                return false;
        }
    }

    private boolean handleCartConnect(Player player) {
        List<Minecart> nearbyMinecarts = getNearbyMinecarts(player.getLocation());

        if (nearbyMinecarts.size() < 2) {
            player.sendMessage("§cNot enough minecarts nearby to connect!");
            return true;
        }

        if (nearbyMinecarts.size() > maxConnectedCarts) {
            player.sendMessage("§cToo many carts! Maximum allowed is " + maxConnectedCarts + ".");
            return true;
        }

        // Check if any of these carts would exceed the limit when connected to existing trains
        for (Minecart cart : nearbyMinecarts) {
            Set<UUID> existingConnections = getAllConnectedCarts(cart.getUniqueId());
            if (existingConnections.size() + nearbyMinecarts.size() > maxConnectedCarts) {
                player.sendMessage("§cConnecting these carts would exceed the maximum of " + maxConnectedCarts + " carts!");
                return true;
            }
        }

        connectMinecarts(nearbyMinecarts);
        player.sendMessage("§aConnected " + nearbyMinecarts.size() + " minecarts!");
        player.sendMessage("§eMake sure to look forward when driving!");
        return true;
    }

    private boolean handleCartDisconnect(Player player) {
        List<Minecart> nearbyMinecarts = getNearbyMinecarts(player.getLocation());

        if (nearbyMinecarts.isEmpty()) {
            player.sendMessage("§cNo minecarts nearby to disconnect!");
            return true;
        }

        disconnectMinecarts(nearbyMinecarts);
        player.sendMessage("§aDisconnected all nearby minecarts!");
        return true;
    }

    private boolean handleCartDriver(Player player) {
        if (!(player.getVehicle() instanceof Minecart)) {
            player.sendMessage("§cYou must be sitting in a minecart!");
            return true;
        }

        Minecart cart = (Minecart) player.getVehicle();
        UUID cartId = cart.getUniqueId();

        if (!isValidDriverCart(cart)) {
            player.sendMessage("§cThis is not a valid cart for drivers.");
            return true;
        }

        Set<UUID> connected = connectedCarts.getOrDefault(cartId, new HashSet<>());
        driverCarts.put(cartId, connected);
        player.sendMessage("§aYou are now controlling the connected minecarts!");
        return true;
    }

    private List<Minecart> getNearbyMinecarts(Location location) {
        List<Minecart> minecarts = new ArrayList<>();
        World world = location.getWorld();
        if (world != null) {
            for (Entity entity : world.getNearbyEntities(location, connectionRadius, connectionRadius, connectionRadius)) {
                if (entity instanceof Minecart) {
                    minecarts.add((Minecart) entity);
                }
            }
        }
        return minecarts;
    }

    private void connectMinecarts(List<Minecart> minecarts) {
        for (int i = 0; i < minecarts.size(); i++) {
            Minecart current = minecarts.get(i);
            UUID currentId = current.getUniqueId();

            Set<UUID> connections = connectedCarts.computeIfAbsent(currentId, k -> new HashSet<>());

            if (i < minecarts.size() - 1) {
                // Connect to next cart
                Minecart next = minecarts.get(i + 1);
                UUID nextId = next.getUniqueId();
                connections.add(nextId);

                // Make sure next cart is connected back
                Set<UUID> nextConnections = connectedCarts.computeIfAbsent(nextId, k -> new HashSet<>());
                nextConnections.add(currentId);
            }
        }
    }

    private void disconnectMinecarts(List<Minecart> minecarts) {
        for (Minecart cart : minecarts) {
            UUID cartId = cart.getUniqueId();
            connectedCarts.remove(cartId);
            driverCarts.remove(cartId);
        }
    }

    private boolean isValidDriverCart(Minecart cart) {
        return !hasCartAhead(cart);
    }

    private boolean hasCartAhead(Minecart cart) {
        Location loc = cart.getLocation();
        Vector direction = cart.getVelocity();

        // If the cart isn't moving, use the cart's facing direction
        if (direction.lengthSquared() < 0.01) {
            float yaw = loc.getYaw();
            direction = new Vector(
                    -Math.sin(Math.toRadians(yaw)),
                    0,
                    Math.cos(Math.toRadians(yaw))
            );
        } else {
            direction = direction.normalize();
        }

        // Check in front of the cart for obstacles
        Location ahead = loc.clone().add(direction.multiply(2));

        try {
            for (Entity entity : cart.getWorld().getNearbyEntities(ahead, 1, 1, 1)) {
                if (entity instanceof Minecart && entity.getUniqueId() != cart.getUniqueId()) {
                    // Check if the found cart is connected to this cart
                    UUID foundCartId = entity.getUniqueId();
                    Set<UUID> connectedToThis = connectedCarts.getOrDefault(cart.getUniqueId(), new HashSet<>());

                    // If the cart ahead is connected to this cart, it's blocking the path
                    if (connectedToThis.contains(foundCartId)) {
                        return true;
                    }
                }
            }
        } catch (IllegalArgumentException e) {
            getLogger().warning("Error checking for carts ahead: " + e.getMessage());
            return false;
        }

        return false;
    }

    public Map<UUID, Set<UUID>> getConnectedCarts() {
        return connectedCarts;
    }

    public Map<UUID, Set<UUID>> getDriverCarts() {
        return driverCarts;
    }

    private Set<UUID> getAllConnectedCarts(UUID startCartId) {
        Set<UUID> allConnected = new HashSet<>();
        Queue<UUID> toProcess = new LinkedList<>();
        toProcess.add(startCartId);

        while (!toProcess.isEmpty()) {
            UUID current = toProcess.poll();
            if (allConnected.add(current)) { // if this cart wasn't already processed
                Set<UUID> connections = connectedCarts.getOrDefault(current, new HashSet<>());
                toProcess.addAll(connections);
            }
        }

        return allConnected;
    }
}