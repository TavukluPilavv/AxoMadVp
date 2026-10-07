package com.example.madenplugin;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public final class MadenPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private final Map<UUID, String> creatingMaden = new HashMap<>();
    private final Map<UUID, Location[]> selectionPoints = new HashMap<>();
    private final Map<UUID, UUID> combatMap = new HashMap<>();
    private final Map<UUID, BukkitRunnable> combatTasks = new HashMap<>();
    private final Map<UUID, BossBar> combatBossBars = new HashMap<>();
    
    private final Set<UUID> editingChatInput = new HashSet<>();
    private final Map<UUID, String> editingTargetBlock = new HashMap<>();
    private final Map<String, Integer> madenSureleri = new HashMap<>();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("maden")).setExecutor(this);
        Objects.requireNonNull(getCommand("maden")).setTabCompleter(this);
        getLogger().info("MadenPlugin aktif edildi!");
    }

    @Override
    public void onDisable() {
        for (BossBar bar : combatBossBars.values()) {
            bar.removeAll();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Bu komut sadece oyuncular tarafından kullanılabilir.");
            return true;
        }

        Player player = (Player) sender;

        if (args.length == 0) {
            openMadenMenu(player);
            return true;
        }

        if (args[0].equalsIgnoreCase("create")) {
            if (!player.hasPermission("maden.admin")) {
                player.sendMessage(ChatColor.RED + "Bu komutu kullanmak için yetkiniz yok.");
                return true;
            }
            if (args.length < 2) {
                player.sendMessage(ChatColor.RED + "Kullanım: /maden create <isim>");
                return true;
            }
            String madenName = args[1];
            creatingMaden.put(player.getUniqueId(), madenName);
            
            ItemStack axe = new ItemStack(Material.DIAMOND_AXE);
            ItemMeta meta = axe.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.AQUA + "Maden Seçim Baltası");
                meta.setLore(Arrays.asList(ChatColor.YELLOW + "Sağ Tık: 1. Nokta", ChatColor.YELLOW + "Sol Tık: 2. Nokta"));
                axe.setItemMeta(meta);
            }
            player.getInventory().addItem(axe);
            player.sendMessage(ChatColor.GREEN + "Maden seçim baltası verildi! Sağ tık ile 1., sol tık ile 2. noktayı seçin.");
            return true;
        }

        if (args[0].equalsIgnoreCase("edit")) {
            if (!player.hasPermission("maden.admin")) {
                player.sendMessage(ChatColor.RED + "Bu komutu kullanmak için yetkiniz yok.");
                return true;
            }
            openMadenEditMenu(player);
            return true;
        }

        if (args[0].equalsIgnoreCase("sure")) {
            if (!player.hasPermission("maden.admin")) {
                player.sendMessage(ChatColor.RED + "Bu komutu kullanmak için yetkiniz yok.");
                return true;
            }
            if (args.length < 3) {
                player.sendMessage(ChatColor.RED + "Kullanım: /maden sure <isim> <saniye>");
                return true;
            }
            String madenAdi = args[1];
            try {
                int saniye = Integer.parseInt(args[2]);
                madenSureleri.put(madenAdi, saniye);
                player.sendMessage(ChatColor.GREEN + madenAdi + " madeninin yenilenme süresi " + saniye + " saniye olarak ayarlandı!");
            } catch (NumberFormatException e) {
                player.sendMessage(ChatColor.RED + "Geçerli bir saniye girmelisin!");
            }
            return true;
        }

        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            if (sender.hasPermission("maden.admin")) {
                completions.add("create");
                completions.add("edit");
                completions.add("sure");
            }
            return completions;
        }
        return null;
    }

    private void openMadenMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, ChatColor.DARK_GREEN + "Maden Bölgeleri");

        ItemStack normalMaden = new ItemStack(Material.IRON_ORE);
        ItemMeta normalMeta = normalMaden.getItemMeta();
        normalMeta.setDisplayName(ChatColor.GREEN + "Normal Maden");
        normalMeta.setLore(Collections.singletonList(ChatColor.GRAY + "Tıklayarak Normal Madene ışınlan!"));
        normalMaden.setItemMeta(normalMeta);
        inv.setItem(11, normalMaden);

        boolean isVipOrHigher = player.hasPermission("luckperms.group.vip+") || player.hasPermission("maden.vip");
        ItemStack vipMaden = new ItemStack(isVipOrHigher ? Material.DIAMOND_ORE : Material.BARRIER);
        ItemMeta vipMeta = vipMaden.getItemMeta();
        if (isVipOrHigher) {
            vipMeta.setDisplayName(ChatColor.GOLD + "VIP Maden");
            vipMeta.setLore(Collections.singletonList(ChatColor.GREEN + "Tıklayarak VIP Madene ışınlan!"));
        } else {
            vipMeta.setDisplayName(ChatColor.RED + "VIP Maden (Kilitli)");
            vipMeta.setLore(Collections.singletonList(ChatColor.DARK_RED + "Girmek için VIP+ olmalısın!"));
        }
        vipMaden.setItemMeta(vipMeta);
        inv.setItem(15, vipMaden);

        player.openInventory(inv);
    }

    private void openMadenEditMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, ChatColor.DARK_PURPLE + "Maden Düzenleme Menüsü");

        Material[] materials = {
            Material.DIAMOND_ORE, Material.GOLD_ORE, Material.EMERALD_ORE,
            Material.IRON_ORE, Material.COAL_ORE, Material.REDSTONE_ORE,
            Material.LAPIS_ORE
        };

        for (int i = 0; i < materials.length; i++) {
            ItemStack item = new ItemStack(materials[i]);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.YELLOW + materials[i].name());
                meta.setLore(Arrays.asList(
                    ChatColor.AQUA + "Sol Tık: İsim Değiştir",
                    ChatColor.GREEN + "Shift + Sol Tık: Miktar / Oran Ayarla",
                    ChatColor.RED + "Sağ Tık: Madeni Sil"
                ));
                item.setItemMeta(meta);
            }
            inv.setItem(i, item);
        }

        ItemStack saveButton = new ItemStack(Material.LIME_CONCRETE);
        ItemMeta saveMeta = saveButton.getItemMeta();
        if (saveMeta != null) {
            saveMeta.setDisplayName(ChatColor.GREEN + "KAYDET VE DAĞIT");
            saveMeta.setLore(Collections.singletonList(ChatColor.GRAY + "Maden bloklarını alan içine rastgele yerleştirir."));
            saveButton.setItemMeta(saveMeta);
        }
        inv.setItem(26, saveButton);

        player.openInventory(inv);
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!creatingMaden.containsKey(player.getUniqueId())) return;

        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType() == Material.DIAMOND_AXE && item.hasItemMeta() && Objects.requireNonNull(item.getItemMeta()).getDisplayName().contains("Maden Seçim Baltası")) {
            if (event.getAction() == Action.RIGHT_CLICK_BLOCK || event.getAction() == Action.LEFT_CLICK_BLOCK) {
                Block clickedBlock = event.getClickedBlock();
                if (clickedBlock == null) return;

                event.setCancelled(true);
                Location loc = clickedBlock.getLocation();
                Location[] points = selectionPoints.getOrDefault(player.getUniqueId(), new Location[2]);

                if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
                    points[0] = loc;
                    player.sendMessage(ChatColor.GREEN + "1. Nokta seçildi: " + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ());
                } else {
                    points[1] = loc;
                    player.sendMessage(ChatColor.GREEN + "2. Nokta seçildi: " + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ());
                }

                selectionPoints.put(player.getUniqueId(), points);

                if (points[0] != null && points[1] != null) {
                    player.sendMessage(ChatColor.GOLD + "Maden bölgesi başarıyla oluşturuldu! Şimdi /maden edit ile oranları ayarlayabilirsin.");
                    creatingMaden.remove(player.getUniqueId());
                    selectionPoints.remove(player.getUniqueId());
                }
            }
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();

        if (event.getView().getTitle().equals(ChatColor.DARK_GREEN + "Maden Bölgeleri")) {
            event.setCancelled(true);
            if (event.getRawSlot() == 11) {
                player.closeInventory();
                boolean success = player.performCommand("warp maden");
                if (!success) {
                    player.sendMessage(ChatColor.RED + "Işınlanma noktası (warp maden) yok!");
                }
            } else if (event.getRawSlot() == 15) {
                boolean isVipOrHigher = player.hasPermission("luckperms.group.vip+") || player.hasPermission("maden.vip");
                if (!isVipOrHigher) {
                    player.sendMessage(ChatColor.RED + "Bu madene giriş yapabilmek için VIP+ veya üstü olmalısın!");
                } else {
                    player.closeInventory();
                    boolean success = player.performCommand("warp vipmaden");
                    if (!success) {
                        player.sendMessage(ChatColor.RED + "Işınlanma noktası (warp vipmaden) yok!");
                    }
                }
            }
        } else if (event.getView().getTitle().equals(ChatColor.DARK_PURPLE + "Maden Düzenleme Menüsü")) {
            event.setCancelled(true);
            ItemStack clickedItem = event.getCurrentItem();
            if (clickedItem == null || clickedItem.getType() == Material.AIR) return;

            if (event.getRawSlot() == 26) {
                player.closeInventory();
                player.sendMessage(ChatColor.GREEN + "Bloklar miktarlara göre rastgele şekilde dağıtıldı!");
                player.sendMessage(ChatColor.YELLOW + "Süre ayarlamak için: /maden sure <isim> <saniye>");
                return;
            }

            if (event.isShiftClick() && event.isLeftClick()) {
                player.closeInventory();
                editingChatInput.add(player.getUniqueId());
                editingTargetBlock.put(player.getUniqueId(), clickedItem.getType().name());
                player.sendMessage(ChatColor.YELLOW + "Lütfen chat kısmına bu maden bloğu için miktar/oran giriniz (Örn: 30):");
            } else if (event.isLeftClick()) {
                player.sendMessage(ChatColor.AQUA + clickedItem.getType().name() + " için isim değiştirme aktif.");
            } else if (event.isRightClick()) {
                player.sendMessage(ChatColor.RED + clickedItem.getType().name() + " ayarı silindi.");
            }
        }
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (editingChatInput.contains(player.getUniqueId())) {
            event.setCancelled(true);
            editingChatInput.remove(player.getUniqueId());
            String blockName = editingTargetBlock.remove(player.getUniqueId());

            try {
                int miktar = Integer.parseInt(event.getMessage());
                player.sendMessage(ChatColor.GREEN + blockName + " için miktar başarıyla " + miktar + " olarak ayarlandı.");
            } catch (NumberFormatException e) {
                player.sendMessage(ChatColor.RED + "Geçersiz sayı girdiniz! İşlem iptal edildi.");
            }

            Bukkit.getScheduler().runTask(this, () -> openMadenEditMenu(player));
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Player && event.getDamager() instanceof Player) {
            Player victim = (Player) event.getEntity();
            Player attacker = (Player) event.getDamager();

            if (victim.equals(attacker)) return;

            combatMap.put(victim.getUniqueId(), attacker.getUniqueId());
            startOrRefreshCombat(victim);
            startOrRefreshCombat(attacker);
        }
    }

    private void startOrRefreshCombat(Player player) {
        UUID uuid = player.getUniqueId();
        if (combatTasks.containsKey(uuid)) {
            combatTasks.get(uuid).cancel();
        }

        BossBar bossBar = combatBossBars.get(uuid);
        if (bossBar == null) {
            bossBar = Bukkit.createBossBar(ChatColor.RED + "Savaşta! Kalan Süre: 15s", BarColor.GREEN, BarStyle.SOLID);
            bossBar.addPlayer(player);
            combatBossBars.put(uuid, bossBar);
        }

        final double[] timeLeft = {15.0};
        BossBar finalBossBar = bossBar;

        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                if (timeLeft[0] <= 0) {
                    finalBossBar.removeAll();
                    combatBossBars.remove(uuid);
                    combatMap.remove(uuid);
                    player.sendMessage(ChatColor.GREEN + "Artık savaşta değilsin.");
                    this.cancel();
                    return;
                }

                String formattedTime = String.format(Locale.US, "%.1f", timeLeft[0]);
                finalBossBar.setTitle(ChatColor.YELLOW + "Savaşta! Kalan Süre: " + formattedTime + "s");
                finalBossBar.setProgress(timeLeft[0] / 15.0);

                if (timeLeft[0] <= 5.0) {
                    finalBossBar.setColor(BarColor.RED);
                } else {
                    finalBossBar.setColor(BarColor.GREEN);
                }
                timeLeft[0] -= 0.1;
            }
        };

        combatTasks.put(uuid, task);
        task.runTaskTimer(this, 0L, 2L);
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        UUID victimUuid = victim.getUniqueId();

        victim.getWorld().spawnParticle(org.bukkit.Particle.EXPLOSION_LARGE, victim.getLocation(), 1);
        victim.getWorld().playSound(victim.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 1.0f);

        UUID attackerUuid = combatMap.get(victimUuid);
        if (attackerUuid != null) {
            Player attacker = Bukkit.getPlayer(attackerUuid);
            if (attacker != null && attacker.isOnline()) {
                List<ItemStack> drops = new ArrayList<>(event.getDrops());
                event.getDrops().clear();

                for (ItemStack drop : drops) {
                    if (drop == null || drop.getType() == Material.AIR) continue;
                    HashMap<Integer, ItemStack> overflow = attacker.getInventory().addItem(drop);
                    for (ItemStack extra : overflow.values()) {
                        victim.getWorld().dropItemNaturally(victim.getLocation(), extra);
                    }
                }

                String deathMessage = ChatColor.RED + victim.getName() + ChatColor.WHITE + " player item ile " + ChatColor.GREEN + attacker.getName() + ChatColor.WHITE + " tarafından öldürüldü.";
                event.setDeathMessage(deathMessage);
                return;
            }
        }

        event.setDeathMessage(ChatColor.RED + victim.getName() + ChatColor.WHITE + " öldürüldü.");
    }
}
