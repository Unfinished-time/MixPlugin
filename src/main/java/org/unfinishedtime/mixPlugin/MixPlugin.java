package org.unfinishedtime.mixPlugin;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;

public final class MixPlugin extends JavaPlugin implements Listener, TabCompleter {

    // 权限节点
    private static final String PERMISSION_PREFIX = "mixplugin.";
    public static final String PERM_BACK = PERMISSION_PREFIX + "back";
    public static final String PERM_TPA = PERMISSION_PREFIX + "tpa";
    public static final String PERM_TPA_ACCEPT = PERMISSION_PREFIX + "tpa.accept";
    public static final String PERM_TPA_DENY = PERMISSION_PREFIX + "tpa.deny";
    public static final String PERM_BAN = PERMISSION_PREFIX + "ban";
    public static final String PERM_UNBAN = PERMISSION_PREFIX + "unban";
    public static final String PERM_BANS = PERMISSION_PREFIX + "bans";
    private static final String PLUGIN_PREFIX = "§8[§6MixPlugin§8] ";
    private static final long REQUEST_TIMEOUT = 120_000; // 2分钟超时
    private final Map<UUID, Location> deathLocations = new HashMap<>();
    private final Map<UUID, UUID> teleportRequests = new HashMap<>();
    private final Map<UUID, Long> requestTimestamps = new HashMap<>();
    private File bansFile;
    private FileConfiguration bansConfig;

    @Override
    public void onEnable() {
        setupConfigs();
        getLogger().info("插件已加载 v1.5.1");
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(this.getCommand("mp")).setTabCompleter(this);
    }
    private void setupConfigs() {
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) getLogger().warning("无法创建插件目录");
        bansFile = new File(getDataFolder(), "bans.yml");
        if (!bansFile.exists()) {
            try {
                if (!bansFile.createNewFile()) getLogger().warning("无法创建 bans.yml");
            } catch (IOException e) {
                getLogger().severe("无法创建 bans.yml: " + e.getMessage());
            }
        }
        bansConfig = YamlConfiguration.loadConfiguration(bansFile);
    }
    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        deathLocations.put(player.getUniqueId(), player.getLocation());
        sendMessage(player, "你的死亡位置已记录，使用 /mp back 可以回到这里");
    }

    @EventHandler
    public void onPlayerLogin(PlayerLoginEvent event) {
        String path = "bans." + event.getPlayer().getUniqueId();
        if (bansConfig.contains(path)) {
            long until = bansConfig.getLong(path + ".until", 0);
            if (until > 0 && System.currentTimeMillis() > until) {
                bansConfig.set(path, null);
                saveBansConfig();
                return;
            }
            String reason = bansConfig.getString(path + ".reason", "违反服务器规则");
            String operator = bansConfig.getString(path + ".operator", "系统");
            String timeLeft = until > 0 ? formatTimeLeft(until) : "永久";
            event.setResult(PlayerLoginEvent.Result.KICK_BANNED);
            event.setKickMessage(
                    "§c§l你已被封禁\n\n" +
                            "§7原因: §f" + reason + "\n" +
                            "§7操作者: §f" + operator + "\n" +
                            "§7剩余时间: §f" + timeLeft + "\n\n" +
                            "§7如有疑问请联系管理员"
            );
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("mp")) return false;
        if (args.length == 0) {
            showHelp(sender);
            return true;
        }
        return switch (args[0].toLowerCase()) {
            case "back" -> handleBackCommand(sender);
            case "tpa" -> handleTpaCommand(sender, args);
            case "ban" -> handleBanCommand(sender, args);
            case "unban" -> handleUnbanCommand(sender, args);
            case "bans" -> showBanList(sender);
            default -> {
                sendMessage(sender, "§c未知命令，输入 /mp 查看帮助");
                yield true;
            }
        };
    }
    private String formatTimeLeft(long until) {
        long left = (until - System.currentTimeMillis()) / 1000;
        long days = left / 86_400;
        long hours = (left % 86_400) / 3_600;
        return String.format("%d天%d小时", days, hours);
    }
    private void saveBansConfig() {
        try {
            bansConfig.save(bansFile);
        } catch (IOException e) {
            getLogger().severe("无法保存封禁数据: " + e.getMessage());
        }
    }
    private boolean handleBackCommand(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sendMessage(sender, "只有玩家才能使用这个命令！");
            return true;
        }

        Location deathLoc = deathLocations.get(player.getUniqueId());
        if (deathLoc == null) {
            sendMessage(player, "你没有可返回的死亡位置！");
            return true;
        }

        if (deathLoc.getWorld() == null) {
            sendMessage(player, "死亡位置所在世界已不存在");
            deathLocations.remove(player.getUniqueId());
            return true;
        }

        player.teleport(deathLoc);
        sendMessage(player, "已传送至你的死亡位置");
        deathLocations.remove(player.getUniqueId());
        return true;
    }
    private boolean handleTpaCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sendMessage(sender, "只有玩家才能使用这个命令！");
            return true;
        }

        if (args.length < 2) {
            sendMessage(player, "用法: /mp tpa <玩家|accept|deny>");
            return true;
        }

        return switch (args[1].toLowerCase()) {
            case "accept" -> acceptTeleportRequest(player);
            case "deny" -> denyTeleportRequest(player);
            default -> sendTeleportRequest(player, args[1]);
        };
    }

    private boolean sendTeleportRequest(Player sender, String targetName) {
        Player target = Bukkit.getPlayer(targetName);
        if (target == null) {
            sendMessage(sender, "玩家 " + targetName + " 不在线或不存在！");
            return true;
        }

        if (sender.equals(target)) {
            sendMessage(sender, "你不能传送给自己！");
            return true;
        }

        teleportRequests.put(target.getUniqueId(), sender.getUniqueId());
        requestTimestamps.put(target.getUniqueId(), System.currentTimeMillis());

        sendMessage(sender, "已向 " + target.getName() + " 发送传送请求");
        sendMessage(target, sender.getName() + " 想传送到你身边");
        sendMessage(target, "输入 /mp tpa accept 接受，/mp tpa deny 拒绝");
        return true;
    }

    private boolean acceptTeleportRequest(Player target) {
        UUID targetId = target.getUniqueId();
        if (!validateRequestTimeout(targetId)) {
            sendMessage(target, "传送请求已过期");
            cleanRequest(targetId);
            return true;
        }

        UUID requesterId = teleportRequests.get(targetId);
        if (requesterId == null) {
            sendMessage(target, "你没有待处理的传送请求！");
            return true;
        }

        Player requester = Bukkit.getPlayer(requesterId);
        if (requester == null) {
            sendMessage(target, "发送请求的玩家已离线");
            cleanRequest(targetId);
            return true;
        }

        requester.teleport(target.getLocation());
        sendMessage(requester, target.getName() + " 接受了你的传送请求");
        sendMessage(target, "已接受 " + requester.getName() + " 的传送请求");
        cleanRequest(targetId);
        return true;
    }

    private boolean denyTeleportRequest(Player target) {
        UUID targetId = target.getUniqueId();
        UUID requesterId = teleportRequests.get(targetId);

        if (requesterId == null) {
            sendMessage(target, "你没有待处理的传送请求！");
            return true;
        }

        Player requester = Bukkit.getPlayer(requesterId);
        if (requester != null) {
            sendMessage(requester, target.getName() + " 拒绝了你的传送请求");
        }
        sendMessage(target, "已拒绝传送请求");
        cleanRequest(targetId);
        return true;
    }

    private boolean handleBanCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendMessage(sender, "§c用法: /mp ban <玩家名> [天数] [原因]");
            sendMessage(sender, "§7示例: /mp ban Player1 7 使用外挂");
            sendMessage(sender, "§7示例: /mp ban Player1 使用外挂");
            sendMessage(sender, "§7示例: /mp ban Player1");
            return true;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sendMessage(sender, "§c玩家不在线或不存在！");
            return true;
        }

        int days = 1;
        String reason = "违反服务器规则";
        int reasonStartIndex = 2;

        if (args.length >= 3) {
            try {
                days = Integer.parseInt(args[2]);
                reasonStartIndex = 3;
            } catch (NumberFormatException e) {
                reasonStartIndex = 2;
            }
        }

        if (args.length > reasonStartIndex) {
            reason = String.join(" ", Arrays.copyOfRange(args, reasonStartIndex, args.length));
        }

        long until = days > 0 ? System.currentTimeMillis() + (days * 86_400_000L) : 0;

        String path = "bans." + target.getUniqueId();
        bansConfig.set(path + ".reason", reason);
        bansConfig.set(path + ".operator", sender.getName());
        bansConfig.set(path + ".until", until);
        bansConfig.set(path + ".date", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));

        saveBansConfig();

        target.kickPlayer(
                "§c§l你已被封禁\n\n" +
                        "§7原因: §f" + reason + "\n" +
                        "§7操作者: §f" + sender.getName() + "\n" +
                        "§7解封时间: §f" + (until > 0 ? "约" + days + "天后" : "永久") + "\n\n" +
                        "§7如有疑问请联系管理员"
        );

        Bukkit.broadcastMessage(
                PLUGIN_PREFIX + "§c玩家 §e" + target.getName() + " §c已被封禁\n" +
                        "§7原因: §f" + reason + "\n" +
                        "§7时长: §f" + (days > 0 ? days + "天" : "永久") + "\n" +
                        "§7操作者: §f" + sender.getName()
        );

        return true;
    }

    private boolean handleUnbanCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendMessage(sender, "§c用法: /mp unban <玩家名/UUID>");
            return true;
        }

        UUID targetUuid = findBannedPlayer(args[1]);
        if (targetUuid == null) {
            sendMessage(sender, "§c未找到该玩家的封禁记录！");
            return true;
        }

        String playerName = Bukkit.getOfflinePlayer(targetUuid).getName();
        bansConfig.set("bans." + targetUuid, null);
        saveBansConfig();

        sendMessage(sender, "§a已解除 §e" + (playerName != null ? playerName : targetUuid.toString()) + " §a的封禁");
        return true;
    }

    private boolean showBanList(CommandSender sender) {
        ConfigurationSection bansSection = bansConfig.getConfigurationSection("bans");
        if (bansSection == null || bansSection.getKeys(false).isEmpty()) {
            sendMessage(sender, "§a当前没有封禁记录");
            return true;
        }

        sender.sendMessage("§8§m----------------§6 封禁列表 §8§m----------------");
        for (String uuidStr : bansSection.getKeys(false)) {
            UUID uuid = UUID.fromString(uuidStr);
            OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
            String name = op.getName() != null ? op.getName() : "未知玩家";

            long until = bansConfig.getLong("bans." + uuidStr + ".until", 0);
            String timeLeft = until > 0 ?
                    formatTimeLeft(until) + "后解封" :
                    "永久封禁";

            sender.sendMessage(String.format(
                    "§c%s §8(§7%s§8)\n§7原因: §f%s\n§7操作者: §f%s\n§7封禁时间: §f%s\n§7状态: §f%s",
                    name,
                    uuidStr.substring(0, 8),
                    bansConfig.getString("bans." + uuidStr + ".reason"),
                    bansConfig.getString("bans." + uuidStr + ".operator"),
                    bansConfig.getString("bans." + uuidStr + ".date"),
                    timeLeft
            ));
            sender.sendMessage("§8§m----------------------------------------");
        }
        return true;
    }

    private UUID findBannedPlayer(String input) {
        ConfigurationSection bansSection = bansConfig.getConfigurationSection("bans");
        if (bansSection == null) return null;

        try {
            UUID uuid = UUID.fromString(input);
            if (bansSection.contains(uuid.toString())) {
                return uuid;
            }
        } catch (IllegalArgumentException ignored) {}

        for (String uuidStr : bansSection.getKeys(false)) {
            OfflinePlayer op = Bukkit.getOfflinePlayer(UUID.fromString(uuidStr));
            if (op.getName() != null && op.getName().equalsIgnoreCase(input)) {
                return op.getUniqueId();
            }
        }
        return null;
    }

    private boolean validateRequestTimeout(UUID targetId) {
        Long requestTime = requestTimestamps.get(targetId);
        return requestTime != null && System.currentTimeMillis() - requestTime <= REQUEST_TIMEOUT;
    }

    private void cleanRequest(UUID targetId) {
        teleportRequests.remove(targetId);
        requestTimestamps.remove(targetId);
    }

    private void sendMessage(CommandSender sender, String message) {
        sender.sendMessage(PLUGIN_PREFIX + message);
    }

    private void showHelp(CommandSender sender) {
        sender.sendMessage(PLUGIN_PREFIX + "§m━━━━━━━━━━━━━━━━━━━━━━━━");
        sender.sendMessage(PLUGIN_PREFIX + "§6MixPlugin 命令帮助");
        sender.sendMessage(PLUGIN_PREFIX + "§e/mp back §7- 回到死亡位置");
        sender.sendMessage(PLUGIN_PREFIX + "§e/mp tpa <玩家> §7- 请求传送");
        sender.sendMessage(PLUGIN_PREFIX + "§e/mp tpa accept §7- 接受传送请求");
        sender.sendMessage(PLUGIN_PREFIX + "§e/mp tpa deny §7- 拒绝传送请求");
        sender.sendMessage(PLUGIN_PREFIX + "§e/mp ban <玩家> [天数] [原因] §7- 封禁玩家");
        sender.sendMessage(PLUGIN_PREFIX + "§e/mp unban <玩家> §7- 解封玩家");
        sender.sendMessage(PLUGIN_PREFIX + "§e/mp bans §7- 查看封禁列表");
        sender.sendMessage(PLUGIN_PREFIX + "§m━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (cmd.getName().equalsIgnoreCase("mp")) {
            if (args.length == 1) {
                completions.addAll(Arrays.asList("back", "tpa", "ban", "unban", "bans"));

                completions.removeIf(s -> {
                    switch (s) {
                        case "back": return !hasPermission(sender, PERM_BACK);
                        case "tpa": return !hasPermission(sender, PERM_TPA);
                        case "ban": return !hasPermission(sender, PERM_BAN);
                        case "unban": return !hasPermission(sender, PERM_UNBAN);
                        case "bans": return !hasPermission(sender, PERM_BANS);
                        default: return true;
                    }
                });
            } else if (args.length == 2) {
                switch (args[0].toLowerCase()) {
                    case "tpa":
                        if (hasPermission(sender, PERM_TPA)) {
                            // 玩家名补全
                            Bukkit.getOnlinePlayers().stream()
                                    .map(Player::getName)
                                    .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                                    .forEach(completions::add);

                            // accept/deny补全
                            if ("accept".startsWith(args[1].toLowerCase())) {
                                completions.add("accept");
                            }
                            if ("deny".startsWith(args[1].toLowerCase())) {
                                completions.add("deny");
                            }
                        }
                        break;
                    case "ban":
                        if (hasPermission(sender, PERM_BAN)) {
                            Bukkit.getOnlinePlayers().stream()
                                    .map(Player::getName)
                                    .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                                    .forEach(completions::add);
                        }
                        break;
                    case "unban":
                        if (hasPermission(sender, PERM_UNBAN)) {
                            ConfigurationSection bansSection = bansConfig.getConfigurationSection("bans");
                            if (bansSection != null) {
                                bansSection.getKeys(false).forEach(uuid -> {
                                    OfflinePlayer op = Bukkit.getOfflinePlayer(UUID.fromString(uuid));
                                    if (op.getName() != null && op.getName().toLowerCase().startsWith(args[1].toLowerCase())) {
                                        completions.add(op.getName());
                                    }
                                });
                            }
                        }
                        break;
                }
            } else if (args.length == 3) {
                if (args[0].equalsIgnoreCase("ban") && hasPermission(sender, PERM_BAN)) {
                    if ("7".startsWith(args[2])) {
                        completions.add("7");
                    }
                    if ("30".startsWith(args[2])) {
                        completions.add("30");
                    }
                    if ("永久".startsWith(args[2])) {
                        completions.add("永久");
                    }
                } else if (args[0].equalsIgnoreCase("tpa") && args[1].equalsIgnoreCase("accept") &&
                        hasPermission(sender, PERM_TPA_ACCEPT)) {
                    // 可添加特殊补全
                }
            } else if (args.length >= 3 && args[0].equalsIgnoreCase("ban") && hasPermission(sender, PERM_BAN)) {
                completions.add("<原因>");
            }
        }

        Collections.sort(completions);
        return completions;
    }

    private boolean hasPermission(CommandSender sender, String permission) {
        return sender.hasPermission(permission) || sender.isOp();
    }
}