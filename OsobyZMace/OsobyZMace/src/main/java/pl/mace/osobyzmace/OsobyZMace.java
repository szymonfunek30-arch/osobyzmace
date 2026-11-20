package pl.mace.osobyzmace;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.jetbrains.annotations.NotNull;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class OsobyZMace extends JavaPlugin implements Listener {

    private static final int MIN_RADIUS = 10;
    private static final int MAX_RADIUS = 100;
    private static final int STEP = 10;
    private static final int DEFAULT_RADIUS = 50;

    private static final int SLOT_MINUS = 11;
    private static final int SLOT_INFO = 13;
    private static final int SLOT_PLUS = 15;

    /** Wybrany zasięg każdego gracza. */
    private final Map<UUID, Integer> radii = new HashMap<>();
    /** Gracze z włączonym podświetleniem -> (osoba z maczugą -> jej obrys). */
    private final Map<UUID, Map<UUID, BlockDisplay>> active = new HashMap<>();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);

        var cmd = getCommand("osobyzmace");
        if (cmd != null) {
            cmd.setExecutor(this::handleCommand);
        }

        Bukkit.getScheduler().runTaskTimer(this, this::tickHighlights, 1L, 1L);
    }

    @Override
    public void onDisable() {
        for (Map<UUID, BlockDisplay> shown : active.values()) {
            removeAll(shown);
        }
        active.clear();
    }

    // ------------------------------------------------------------------ logika

    private int radiusOf(Player player) {
        return radii.getOrDefault(player.getUniqueId(), DEFAULT_RADIUS);
    }

    private static boolean hasMace(Player player) {
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && stack.getType() == Material.MACE) {
                return true;
            }
        }
        return false;
    }

    private boolean qualifies(Player viewer, Player target, int radius) {
        if (target.equals(viewer)) {
            return false;
        }
        if (target.getGameMode() == GameMode.SPECTATOR || !viewer.canSee(target)) {
            return false;
        }
        if (!target.getWorld().equals(viewer.getWorld())) {
            return false;
        }
        if (viewer.getLocation().distanceSquared(target.getLocation()) > (double) radius * radius) {
            return false;
        }
        return hasMace(target);
    }

    private List<Player> findHolders(Player viewer, int radius) {
        List<Player> found = new ArrayList<>();
        for (Player target : viewer.getWorld().getPlayers()) {
            if (qualifies(viewer, target, radius)) {
                found.add(target);
            }
        }
        found.sort((a, b) -> Double.compare(
                viewer.getLocation().distanceSquared(a.getLocation()),
                viewer.getLocation().distanceSquared(b.getLocation())));
        return found;
    }

    // ------------------------------------------------------------------ podświetlanie

    private void tickHighlights() {
        Iterator<Map.Entry<UUID, Map<UUID, BlockDisplay>>> it = active.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Map<UUID, BlockDisplay>> entry = it.next();
            Player viewer = Bukkit.getPlayer(entry.getKey());
            if (viewer == null || !viewer.isOnline()) {
                removeAll(entry.getValue());
                it.remove();
                continue;
            }
            updateViewer(viewer, entry.getValue());
        }
    }

    private void updateViewer(Player viewer, Map<UUID, BlockDisplay> shown) {
        int radius = radiusOf(viewer);
        Set<UUID> keep = new HashSet<>();

        for (Player target : viewer.getWorld().getPlayers()) {
            if (!qualifies(viewer, target, radius)) {
                continue;
            }
            keep.add(target.getUniqueId());

            Location loc = target.getLocation();
            loc.setYaw(0f);
            loc.setPitch(0f);

            BlockDisplay display = shown.get(target.getUniqueId());
            if (display == null || !display.isValid()) {
                shown.put(target.getUniqueId(), spawnBox(viewer, loc));
            } else {
                display.teleport(loc);
            }
        }

        shown.entrySet().removeIf(e -> {
            if (keep.contains(e.getKey())) {
                return false;
            }
            e.getValue().remove();
            return true;
        });
    }

    /** Czerwony, świecący obrys wielkości gracza - widoczny tylko dla "viewer". */
    private BlockDisplay spawnBox(Player viewer, Location loc) {
        BlockDisplay box = loc.getWorld().spawn(loc, BlockDisplay.class, display -> {
            display.setVisibleByDefault(false);
            display.setPersistent(false);
            display.setBlock(Material.RED_STAINED_GLASS.createBlockData());
            display.setTransformation(new Transformation(
                    new Vector3f(-0.3f, 0f, -0.3f),
                    new AxisAngle4f(),
                    new Vector3f(0.6f, 1.8f, 0.6f),
                    new AxisAngle4f()));
            display.setViewRange(2.0f);
            display.setGlowing(true);
            display.setGlowColorOverride(Color.RED);
        });
        viewer.showEntity(this, box);
        return box;
    }

    private static void removeAll(Map<UUID, BlockDisplay> shown) {
        for (BlockDisplay display : shown.values()) {
            display.remove();
        }
        shown.clear();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Map<UUID, BlockDisplay> shown = active.remove(event.getPlayer().getUniqueId());
        if (shown != null) {
            removeAll(shown);
        }
    }

    // ------------------------------------------------------------------ okno

    private static final class Gui implements InventoryHolder {
        private Inventory inventory;

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    private static Component line(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private static ItemStack item(Material material, Component name, List<Component> lore) {
        ItemStack stack = new ItemStack(material);
        stack.editMeta(meta -> {
            meta.displayName(name.decoration(TextDecoration.ITALIC, false));
            if (!lore.isEmpty()) {
                meta.lore(lore);
            }
        });
        return stack;
    }

    private void fillGui(Inventory inv, Player viewer) {
        int radius = radiusOf(viewer);
        boolean on = active.containsKey(viewer.getUniqueId());

        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, filler);
        }

        inv.setItem(SLOT_MINUS, item(Material.RED_CONCRETE,
                Component.text("- " + STEP + " klatek", NamedTextColor.RED),
                List.of(line("Zasięg: " + radius + " klatek", NamedTextColor.GRAY))));
        inv.setItem(SLOT_PLUS, item(Material.LIME_CONCRETE,
                Component.text("+ " + STEP + " klatek", NamedTextColor.GREEN),
                List.of(line("Zasięg: " + radius + " klatek", NamedTextColor.GRAY))));

        List<Component> lore = new ArrayList<>();
        lore.add(line("Zasięg: " + radius + " klatek (max " + MAX_RADIUS + ")", NamedTextColor.YELLOW));
        List<Player> found = findHolders(viewer, radius);
        lore.add(line("Osób z maczugą w zasięgu: " + found.size(), NamedTextColor.GRAY));
        for (int i = 0; i < Math.min(found.size(), 8); i++) {
            Player target = found.get(i);
            int dist = (int) Math.round(viewer.getLocation().distance(target.getLocation()));
            lore.add(line(" - " + target.getName() + " (" + dist + " m)", NamedTextColor.GOLD));
        }
        if (found.size() > 8) {
            lore.add(line(" ... i " + (found.size() - 8) + " więcej", NamedTextColor.GRAY));
        }
        lore.add(line(" ", NamedTextColor.GRAY));
        lore.add(line("Kliknij, aby " + (on ? "wyłączyć" : "włączyć") + " podświetlenie",
                NamedTextColor.AQUA));

        inv.setItem(SLOT_INFO, item(on ? Material.ENDER_EYE : Material.ENDER_PEARL,
                Component.text("Podświetlenie: " + (on ? "WŁĄCZONE" : "WYŁĄCZONE"),
                        on ? NamedTextColor.GREEN : NamedTextColor.RED),
                lore));
    }

    private boolean handleCommand(@NotNull CommandSender sender, @NotNull Command command,
                                  @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Tej komendy może użyć tylko gracz.");
            return true;
        }
        Gui holder = new Gui();
        Inventory inv = Bukkit.createInventory(holder, 27,
                Component.text("Osoby z maczugą", NamedTextColor.DARK_GRAY));
        holder.inventory = inv;
        fillGui(inv, player);
        player.openInventory(inv);
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Gui)) {
            return;
        }
        event.setCancelled(true);

        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        int slot = event.getRawSlot();
        UUID uuid = player.getUniqueId();

        if (slot == SLOT_MINUS) {
            radii.put(uuid, Math.max(MIN_RADIUS, radiusOf(player) - STEP));
        } else if (slot == SLOT_PLUS) {
            radii.put(uuid, Math.min(MAX_RADIUS, radiusOf(player) + STEP));
        } else if (slot == SLOT_INFO) {
            Map<UUID, BlockDisplay> shown = active.remove(uuid);
            if (shown != null) {
                removeAll(shown);
            } else {
                active.put(uuid, new HashMap<>());
            }
        } else {
            return;
        }
        fillGui(event.getView().getTopInventory(), player);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Gui) {
            event.setCancelled(true);
        }
    }
}
