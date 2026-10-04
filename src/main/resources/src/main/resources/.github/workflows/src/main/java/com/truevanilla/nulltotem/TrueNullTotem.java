package com.truevanilla.nulltotem;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class TrueNullTotem extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    static class TeamData {
        final UUID id;
        String name;
        UUID owner;
        final Set<UUID> members = new LinkedHashSet<>();
        boolean hasTotem;

        TeamData(UUID id, String name, UUID owner) {
            this.id = id;
            this.name = name;
            this.owner = owner;
            this.members.add(owner);
        }
    }

    private static final String ADMIN_PERM = "truenulltotem.admin";

    private final Map<UUID, TeamData> teams = new HashMap<>();
    private final Map<UUID, UUID> playerTeam = new HashMap<>();
    private final Set<UUID> wiping = new HashSet<>();
    private final Set<UUID> pendingTotemOwners = new HashSet<>();
    private NamespacedKey totemKey;

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void onEnable() {
        totemKey = new NamespacedKey(this, "null_totem_team");
        saveDefaultConfig();
        loadData();

        PluginCommand cmd = getCommand("nullteam");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("TrueNullTotem enabled for Paper 1.21.x");
    }

    @Override
    public void onDisable() {
        saveData();
    }

    // ---------------------------------------------------------------- helpers

    private Component color(String legacy) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(legacy);
    }

    private String prefix() {
        return getConfig().getString("messages.prefix", "&8[&5NullTotem&8] &r");
    }

    /** Message from config (falls back to the given default), with %key% replacements. */
    private String m(String key, String def, String... kv) {
        String s = getConfig().getString("messages." + key, def);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            s = s.replace(kv[i], kv[i + 1]);
        }
        return s;
    }

    private void send(CommandSender to, String legacy) {
        to.sendMessage(color(prefix() + legacy));
    }

    private TeamData teamOf(UUID player) {
        UUID id = playerTeam.get(player);
        return id == null ? null : teams.get(id);
    }

    private TeamData findTeam(String name) {
        for (TeamData t : teams.values()) {
            if (t.name.equalsIgnoreCase(name)) return t;
        }
        return null;
    }

    private String nameOf(UUID uuid) {
        String n = Bukkit.getOfflinePlayer(uuid).getName();
        return n == null ? uuid.toString() : n;
    }

    // ---------------------------------------------------------------- totem item

    private ItemStack createNullTotem(TeamData team) {
        ItemStack item = new ItemStack(Material.TOTEM_OF_UNDYING);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(color("&5&lNull Totem &7(" + team.name + ")")
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                color("&7Team: &f" + team.name).decoration(TextDecoration.ITALIC, false),
                color("&dWhen it triggers, the whole team dies.").decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(totemKey, PersistentDataType.STRING, team.id.toString());
        item.setItemMeta(meta);
        return item;
    }

    private boolean isNullTotem(ItemStack item, UUID teamId) {
        if (item == null || item.getType() != Material.TOTEM_OF_UNDYING || !item.hasItemMeta()) return false;
        String v = item.getItemMeta().getPersistentDataContainer().get(totemKey, PersistentDataType.STRING);
        return teamId.toString().equals(v);
    }

    private void giveTotem(TeamData team, Player player) {
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(createNullTotem(team));
        for (ItemStack extra : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), extra);
        }
        team.hasTotem = true;
    }

    // ---------------------------------------------------------------- events

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player p = event.getEntity();
        TeamData team = teamOf(p.getUniqueId());
        if (team == null || wiping.contains(team.id)) return;
        Bukkit.getScheduler().runTask(this, () -> wipeTeam(team, p.getUniqueId(), "member death"));
    }

    @EventHandler(ignoreCancelled = true)
    public void onResurrect(EntityResurrectEvent event) {
        if (!(event.getEntity() instanceof Player p)) return;
        TeamData team = teamOf(p.getUniqueId());
        if (team == null || wiping.contains(team.id)) return;

        EquipmentSlot hand = event.getHand();
        ItemStack used;
        if (hand == EquipmentSlot.OFF_HAND) {
            used = p.getInventory().getItemInOffHand();
        } else if (hand == EquipmentSlot.HAND) {
            used = p.getInventory().getItemInMainHand();
        } else {
            used = isNullTotem(p.getInventory().getItemInMainHand(), team.id)
                    ? p.getInventory().getItemInMainHand()
                    : p.getInventory().getItemInOffHand();
        }
        if (!isNullTotem(used, team.id)) return;

        p.sendMessage(color(prefix() + m("totem-triggered", "&5&lNULL TOTEM ACTIVATED!")));
        Bukkit.getScheduler().runTask(this, () -> wipeTeam(team, p.getUniqueId(), "Null Totem activation"));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        String path = "pending-deaths." + p.getUniqueId();
        if (!getConfig().contains(path)) return;
        getConfig().set(path, null);
        saveConfig();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (p.isOnline() && !p.isDead()) {
                p.sendMessage(color(prefix()
                        + "&dYour team's Null Totem link triggered while you were offline. &cYou have died."));
                p.setHealth(0.0);
            }
        }, 20L);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player p = event.getPlayer();
        if (!pendingTotemOwners.remove(p.getUniqueId())) return;
        TeamData team = teamOf(p.getUniqueId());
        if (team == null) return;
        Bukkit.getScheduler().runTask(this, () -> giveTotem(team, p));
    }

    // ---------------------------------------------------------------- wipe logic

    private void wipeTeam(TeamData team, UUID trigger, String reason) {
        if (!wiping.add(team.id)) return;
        try {
            getLogger().info("Team " + team.name + " wiped by " + nameOf(trigger) + " (" + reason + ").");
            boolean pending = getConfig().getBoolean("pending-offline-death", true);
            for (UUID member : new ArrayList<>(team.members)) {
                Player p = Bukkit.getPlayer(member);
                if (p != null && p.isOnline()) {
                    if (!p.isDead()) {
                        p.sendMessage(color(prefix()
                                + "&dYour team's Null Totem link triggered. &cThe whole team has died."));
                        p.setHealth(0.0);
                    }
                } else if (pending) {
                    getConfig().set("pending-deaths." + member, true);
                }
            }
            if (getConfig().getBoolean("respawn-totem-after-wipe", false)) {
                pendingTotemOwners.add(team.owner);
            }
            saveConfig();
        } finally {
            wiping.remove(team.id);
        }
    }

    // ---------------------------------------------------------------- commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("admin")) {
            help(sender);
            return true;
        }
        if (!sender.hasPermission(ADMIN_PERM)) {
            send(sender, m("no-permission", "&cYou don't have permission to use this."));
            return true;
        }
        if (args.length < 2) {
            help(sender);
            return true;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "create" -> adminCreate(sender, args);
            case "add" -> adminAdd(sender, args);
            case "remove" -> adminRemove(sender, args);
            case "give" -> adminGive(sender, args);
            case "info" -> adminInfo(sender, args);
            case "disband" -> adminDisband(sender, args);
            case "reload" -> {
                reloadConfig();
                loadData();
                send(sender, m("reload", "&aConfiguration reloaded."));
            }
            default -> help(sender);
        }
        return true;
    }

    private void help(CommandSender sender) {
        if (!sender.hasPermission(ADMIN_PERM)) {
            send(sender, "&cPlayers cannot create or manage Null Totems. Only the server owner/admin can configure them.");
            return;
        }
        send(sender, "&d/nullteam admin create <team> <owner> &7- create and assign a team");
        send(sender, "&d/nullteam admin add <team> <player> &7- add a member");
        send(sender, "&d/nullteam admin remove <team> <player> &7- remove a member");
        send(sender, "&d/nullteam admin give <team> <player> &7- give that team's Null Totem");
        send(sender, "&d/nullteam admin info <team> &7- show team details");
        send(sender, "&d/nullteam admin disband <team> &7- delete a team");
        send(sender, "&d/nullteam admin reload &7- reload configuration");
    }

    private Player onlinePlayer(CommandSender sender, String name) {
        Player p = Bukkit.getPlayerExact(name);
        if (p == null) send(sender, m("player-not-found", "&cPlayer not found.") + " &7(must be online)");
        return p;
    }

    private TeamData requireTeam(CommandSender sender, String name) {
        TeamData t = findTeam(name);
        if (t == null) send(sender, m("team-not-found", "&cThat team does not exist."));
        return t;
    }

    private void adminCreate(CommandSender sender, String[] a) {
        if (a.length < 4) {
            send(sender, "&cUsage: /nullteam admin create <team> <owner>");
            return;
        }
        String name = a[2].replaceAll("[^A-Za-z0-9_-]", "");
        if (name.length() < 2 || name.length() > 16) {
            send(sender, "&cTeam name must be 2-16 characters.");
            return;
        }
        if (findTeam(name) != null) {
            send(sender, "&cThat team name is already taken.");
            return;
        }
        Player owner = onlinePlayer(sender, a[3]);
        if (owner == null) return;
        if (playerTeam.containsKey(owner.getUniqueId())) {
            send(sender, "&cThat player is already in a team.");
            return;
        }
        TeamData team = new TeamData(UUID.randomUUID(), name, owner.getUniqueId());
        teams.put(team.id, team);
        playerTeam.put(owner.getUniqueId(), team.id);
        giveTotem(team, owner);
        saveData();
        send(sender, m("team-created", "&aTeam &f%team% &ahas been created.", "%team%", name));
        send(owner, "&dYou are the owner of Null Totem team &f" + name + "&d.");
    }

    private void adminAdd(CommandSender sender, String[] a) {
        if (a.length < 4) {
            send(sender, "&cUsage: /nullteam admin add <team> <player>");
            return;
        }
        TeamData team = requireTeam(sender, a[2]);
        if (team == null) return;
        Player p = onlinePlayer(sender, a[3]);
        if (p == null) return;
        if (team.members.size() >= getConfig().getInt("max-team-size", 10)) {
            send(sender, m("team-full", "&cThat team is full."));
            return;
        }
        if (playerTeam.containsKey(p.getUniqueId())) {
            send(sender, m("already-member", "&cThat player is already in the team."));
            return;
        }
        team.members.add(p.getUniqueId());
        playerTeam.put(p.getUniqueId(), team.id);
        saveData();
        send(sender, m("player-added", "&a%player% &ahas been added to team &f%team%&a.",
                "%player%", p.getName(), "%team%", team.name));
        send(p, "&aYou were added to Null Totem team &d" + team.name + "&a by the server owner.");
    }

    private void adminRemove(CommandSender sender, String[] a) {
        if (a.length < 4) {
            send(sender, "&cUsage: /nullteam admin remove <team> <player>");
            return;
        }
        TeamData team = requireTeam(sender, a[2]);
        if (team == null) return;
        UUID target = null;
        for (UUID u : team.members) {
            if (nameOf(u).equalsIgnoreCase(a[3])) target = u;
        }
        if (target == null) {
            send(sender, m("not-member", "&cThat player is not in the team."));
            return;
        }
        if (target.equals(team.owner)) {
            send(sender, "&cYou can't remove the team owner. Disband the team instead.");
            return;
        }
        team.members.remove(target);
        playerTeam.remove(target);
        getConfig().set("pending-deaths." + target, null);
        saveData();
        send(sender, m("player-removed", "&c%player% &chas been removed from team &f%team%&c.",
                "%player%", nameOf(target), "%team%", team.name));
        Player online = Bukkit.getPlayer(target);
        if (online != null) {
            send(online, "&cYou were removed from Null Totem team &d" + team.name + "&c by the server owner.");
        }
    }

    private void adminGive(CommandSender sender, String[] a) {
        if (a.length < 4) {
            send(sender, "&cUsage: /nullteam admin give <team> <player>");
            return;
        }
        TeamData team = requireTeam(sender, a[2]);
        if (team == null) return;
        Player p = onlinePlayer(sender, a[3]);
        if (p == null) return;
        if (!team.members.contains(p.getUniqueId())) {
            send(sender, "&cThat player is not a member of this team.");
            return;
        }
        giveTotem(team, p);
        saveData();
        send(sender, m("totem-given", "&dNull Totem given to &f%player%&d.", "%player%", p.getName()));
        send(p, "&dThe server owner gave you the Null Totem for team &f" + team.name + "&d.");
    }

    private void adminInfo(CommandSender sender, String[] a) {
        if (a.length < 3) {
            send(sender, "&cUsage: /nullteam admin info <team>");
            return;
        }
        TeamData team = requireTeam(sender, a[2]);
        if (team == null) return;
        send(sender, "&dTeam: &f" + team.name + " &7(" + team.members.size() + " members)");
        send(sender, "&dOwner: &f" + nameOf(team.owner));
        send(sender, "&dNull Totem: " + (team.hasTotem ? "&aassigned" : "&cnot assigned"));
        for (UUID u : team.members) {
            send(sender, "&7- &f" + nameOf(u));
        }
    }

    private void adminDisband(CommandSender sender, String[] a) {
        if (a.length < 3) {
            send(sender, "&cUsage: /nullteam admin disband <team>");
            return;
        }
        TeamData team = requireTeam(sender, a[2]);
        if (team == null) return;
        for (UUID u : team.members) {
            playerTeam.remove(u);
        }
        teams.remove(team.id);
        saveData();
        send(sender, "&aDisbanded team &d" + team.name + "&a. Existing Null Totems for it are now inactive.");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(ADMIN_PERM)) return List.of();
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.add("admin");
        } else if (args.length == 2) {
            out.addAll(List.of("create", "add", "remove", "give", "info", "disband", "reload"));
        } else if (args.length == 3 && !args[1].equalsIgnoreCase("create") && !args[1].equalsIgnoreCase("reload")) {
            for (TeamData t : teams.values()) out.add(t.name);
        } else if (args.length == 4 && !args[1].equalsIgnoreCase("info") && !args[1].equalsIgnoreCase("disband")) {
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        }
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }

    // ---------------------------------------------------------------- persistence

    private void loadData() {
        teams.clear();
        playerTeam.clear();
        ConfigurationSection sec = getConfig().getConfigurationSection("teams");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                String base = "teams." + key;
                String name = getConfig().getString(base + ".name", key);
                UUID owner = UUID.fromString(getConfig().getString(base + ".owner", ""));
                TeamData t = new TeamData(id, name, owner);
                for (String s : getConfig().getStringList(base + ".members")) {
                    t.members.add(UUID.fromString(s));
                }
                t.hasTotem = getConfig().getBoolean(base + ".has-totem", false);
                teams.put(id, t);
                for (UUID u : t.members) playerTeam.put(u, id);
            } catch (Exception e) {
                getLogger().warning("Skipping broken team entry: " + key);
            }
        }
    }

    private void saveData() {
        getConfig().set("teams", null);
        getConfig().set("player-teams", null);
        for (TeamData t : teams.values()) {
            String base = "teams." + t.id;
            getConfig().set(base + ".name", t.name);
            getConfig().set(base + ".owner", t.owner.toString());
            List<String> members = new ArrayList<>();
            for (UUID u : t.members) {
                members.add(u.toString());
                getConfig().set("player-teams." + u, t.id.toString());
            }
            getConfig().set(base + ".members", members);
            getConfig().set(base + ".has-totem", t.hasTotem);
        }
        saveConfig();
    }
  }
