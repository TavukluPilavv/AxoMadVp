package com.example.madenplugin;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;

import java.text.SimpleDateFormat;
import java.util.*;

public final class MadenPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private final Map<UUID, String> creatingMaden = new HashMap<>();
    private final Map<UUID, Location[]> selectionPoints = new HashMap<>();
    private final Map<UUID, UUID> combatMap = new HashMap<>();
    private final Map<UUID, BukkitRunnable> combatTasks = new HashMap<>();
    private final Map<UUID, BossBar> combatBossBars = new HashMap<>();
    
    private final Set<String> registeredMadens = new HashSet<>();
    private final Map<String, Integer> madenSureleri = new HashMap<>();
    private final Map<String, Map<Material, Integer>> madenOranlari = new HashMap<>();
    private final Map<String, List<Block>> madenBloklari = new HashMap<>();
    
    private final Map<String, BukkitRunnable> activeMadenTimers = new HashMap<>();
    private final Map<String, Integer> madenKalanSureler = new HashMap<>();

    private final Map<Material, Double> basePrices = new HashMap<>();
    private final Map<UUID, Boolean> autoSellMap = new HashMap<>();
    private final Map<UUID, Integer> killStreaks = new HashMap<>();

    // Sohbet üzerinden miktar girişi takibi
    private final Set<UUID> editingChatInput = new HashSet<>();
    private final Map<UUID, String> editingTargetMaden = new HashMap<>();
    private final Map<UUID, Material> editingTargetBlock = new HashMap<>();

    private static Economy economy = null;
    private Scoreboard healthScoreboard;

    @Override
    public void onEnable() {
        if (!setupEconomy()) {
            getLogger().warning("Vault ekonomisi bulunamadı! Otomatik para yatırma çalışmayabilir.");
        }

        // Baz Fiyatlar (Max 8'e kadar çıkabilen aralıklar için temel değerler)
        basePrices.put(Material.DIAMOND_ORE, 4.5);
        basePrices.put(Material.EMERALD_ORE, 6.0);
        basePrices.put(Material.GOLD_ORE, 3.5);
        basePrices.put(Material.IRON_ORE, 2.5);
        basePrices.put(Material.LAPIS_ORE, 2.0);
        basePrices.put(Material.NETHER_QUARTZ_ORE, 1.8);
        basePrices.put(Material.REDSTONE_ORE, 1.5);
        basePrices.put(Material.COAL_ORE, 1.0);

        setupHealthScoreboard();
        startGlobalActionbarTask();

        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("maden")).setExecutor(this);
        Objects.requireNonNull(getCommand("maden")).setTabCompleter(this);
        Objects.requireNonNull(getCommand("irp")).setExecutor(this);
        Objects.requireNonNull(getCommand("sat")).setExecutor(this);
        
        getLogger().info("MadenPlugin başarıyla aktif edildi!");
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        economy = rsp.getProvider();
        return economy != null;
    }

    @Override
    public void onDisable() {
        for (BossBar bar : combatBossBars.values()) bar.removeAll();
        for (BukkitRunnable task : activeMadenTimers.values()) task.cancel();
    }

    private void setupHealthScoreboard() {
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) return;
        healthScoreboard = manager.getNewScoreboard();
        Objective objective = healthScoreboard.registerNewObjective("showhealth", "health", ChatColor.RED + "HP");
        objective.setDisplaySlot(DisplaySlot.BELOW_NAME);
    }

    private void sendActionBar(Player player, String message) {
        player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, net.md_5.bungee.api.chat.TextComponent.fromLegacyText(message));
    }

    private void startGlobalActionbarTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (madenKalanSureler.isEmpty()) return;
                
                StringBuilder sb = new StringBuilder();
                for (Map.Entry<String, Integer> entry : madenKalanSureler.entrySet()) {
                    sb.append(ChatColor.YELLOW).append(entry.getKey().toUpperCase()).append(": ").append(ChatColor.RED).append(entry.getValue()).append("s   ");
                }

                String text = sb.toString().trim();
                if (!text.isEmpty()) {
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        sendActionBar(p, text);
                    }
                }
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        if (healthScoreboard != null) p.setScoreboard(healthScoreboard);
        autoSellMap.putIfAbsent(p.getUniqueId(), true);
        killStreaks.putIfAbsent(p.getUniqueId(), 0);

        event.setJoinMessage(null);
        for (Player online : Bukkit.getOnlinePlayers()) {
            sendActionBar(online, ChatColor.GREEN + "+ " + p.getName() + " sunucuya katıldı!");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        event.setQuitMessage(null);
        for (Player online : Bukkit.getOnlinePlayers()) {
            sendActionBar(online, ChatColor.RED + "- " + p.getName() + " sunucudan ayrıldı.");
        }
    }

    private void startContinuousMadenTimer(String madenAdi) {
        if (activeMadenTimers.containsKey(madenAdi)) {
            activeMadenTimers.get(madenAdi).cancel();
        }

        int sureSaniye = madenSureleri.getOrDefault(madenAdi, 30);
        madenKalanSureler.put(madenAdi, sureSaniye);

        BukkitRunnable timerTask = new BukkitRunnable() {
            int kalanSure = sureSaniye;

            @Override
            public void run() {
                if (kalanSure <= 0) {
                    fillMadenRandomly(madenAdi);
                    kalanSure = madenSureleri.getOrDefault(madenAdi, 30);
                } else {
                    kalanSure--;
                }
                madenKalanSureler.put(madenAdi, kalanSure);
            }
        };

        timerTask.runTaskTimer(this, 0L, 20L);
        activeMadenTimers.put(madenAdi, timerTask);
    }

    private void fillMadenRandomly(String madenName) {
        List<Block> blocks = madenBloklari.get(madenName);
        if (blocks == null || blocks.isEmpty()) return;

        Map<Material, Integer> oranlar = madenOranlari.get(madenName);
        if (oranlar == null || oranlar.isEmpty()) return;

        Random random = new Random();
        for (Block b : blocks) {
            Material chosenMat = Material.STONE;
            int roll = random.nextInt(100) + 1;
            int currentSum = 0;
            for (Map.Entry<Material, Integer> entry : oranlar.entrySet()) {
                currentSum += entry.getValue();
                if (roll <= currentSum) {
                    chosenMat = entry.getKey();
                    break;
                }
            }
            b.setType(chosenMat);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("sat")) {
            if (!(sender instanceof Player)) return true;
            Player p = (Player) sender;
            boolean current = autoSellMap.getOrDefault(p.getUniqueId(), true);
            autoSellMap.put(p.getUniqueId(), !current);
            p.sendMessage(ChatColor.YELLOW + "Otomatik maden satışı: " + (!current ? ChatColor.GREEN + "AÇIK" : ChatColor.RED + "KAPALI"));
            return true;
        }

        if (command.getName().equalsIgnoreCase("irp")) {
            if (!(sender instanceof Player)) return true;
            Player p = (Player) sender;
            if (!p.hasPermission("maden.admin")) {
                p.sendMessage(ChatColor.RED + "Bu komutu kullanmak için yetkiniz yok.");
                return true;
            }
            openIrpMenu(p);
            return true;
        }

        if (!(sender instanceof Player)) return true;
        Player player = (Player) sender;

        if (args.length == 0) {
            openMadenMenu(player);
            return true;
        }

        if (args[0].equalsIgnoreCase("create")) {
            if (!player.hasPermission("maden.admin")) return true;
            if (args.length < 2) {
                player.sendMessage(ChatColor.RED + "Kullanım: /maden create <isim>");
                return true;
            }
            String madenName = args[1].toLowerCase();
            creatingMaden.put(player.getUniqueId(), madenName);
            
            ItemStack axe = new ItemStack(Material.DIAMOND_AXE);
            ItemMeta meta = axe.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.AQUA + "Maden Seçim Baltası");
                meta.setLore(Arrays.asList(ChatColor.YELLOW + "Sağ Tık: 1. Nokta", ChatColor.YELLOW + "Sol Tık: 2. Nokta"));
                axe.setItemMeta(meta);
            }
            player.getInventory().addItem(axe);
            player.sendMessage(ChatColor.GREEN + "Maden seçim baltası verildi!");
            return true;
        }

        if (args[0].equalsIgnoreCase("edit")) {
            if (!player.hasPermission("maden.admin")) return true;
            if (args.length > 1) {
                openMadenDetailEditMenu(player, args[1].toLowerCase());
            } else {
                openMadenListMenu(player);
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("dagit")) {
            if (!player.hasPermission("maden.admin")) return true;
            if (args.length < 2) {
                player.sendMessage(ChatColor.RED + "Kullanım: /maden <isim> dagit");
                return true;
            }
            String madenName = args[1].toLowerCase();
            if (!registeredMadens.contains(madenName)) {
                player.sendMessage(ChatColor.RED + "Böyle bir maden bulunamadı!");
                return true;
            }
            if (!madenOranlari.containsKey(madenName) || madenOranlari.get(madenName).isEmpty()) {
                player.sendMessage(ChatColor.RED + "Lütfen önce /maden edit " + madenName + " ile miktar ayarlayın!");
                return true;
            }

            fillMadenRandomly(madenName);
            player.sendMessage(ChatColor.GREEN + "'" + madenName + "' madeni başarıyla dağıtıldı!");
            return true;
        }

        if (args[0].equalsIgnoreCase("fiyat")) {
            if (!player.hasPermission("maden.admin")) return true;
            if (args.length < 3) {
                player.sendMessage(ChatColor.RED + "Kullanım: /maden fiyat <maden_turu> <fiyat>");
                return true;
            }
            try {
                Material mat = Material.valueOf(args[1].toUpperCase());
                double price = Double.parseDouble(args[2]);
                basePrices.put(mat, price);
                player.sendMessage(ChatColor.GREEN + mat.name() + " fiyatı " + price + " olarak güncellendi!");
            } catch (Exception e) {
                player.sendMessage(ChatColor.RED + "Geçersiz malzeme veya fiyat!");
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("sure")) {
            if (!player.hasPermission("maden.admin")) return true;
            if (args.length < 3) {
                player.sendMessage(ChatColor.RED + "Kullanım: /maden sure <isim> <saniye>");
                return true;
            }
            String madenAdi = args[1].toLowerCase();
            try {
                int saniye = Integer.parseInt(args[2]);
                madenSureleri.put(madenAdi, saniye);
                player.sendMessage(ChatColor.GREEN + madenAdi + " yenilenme süresi " + saniye + "s yapıldı.");
                startContinuousMadenTimer(madenAdi);
            } catch (NumberFormatException e) {
                player.sendMessage(ChatColor.RED + "Geçerli bir sayı girin!");
            }
            return true;
        }

        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("maden")) {
            if (args.length == 1) {
                return Arrays.asList("create", "edit", "dagit", "sure", "fiyat");
            } else if (args.length == 2 && (args[0].equalsIgnoreCase("edit") || args[0].equalsIgnoreCase("sure") || args[0].equalsIgnoreCase("dagit"))) {
                return new ArrayList<>(registeredMadens);
            }
        }
        return null;
    }

    // Blok Kırıldığında Otomatik Satış, Vault Para Yatırma ve Sadece Oyuncuya Özel Actionbar Fiyat Bildirimi
    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (autoSellMap.getOrDefault(player.getUniqueId(), true)) {
            Material type = event.getBlock().getType();
            if (basePrices.containsKey(type)) {
                event.setDropItems(false);
                event.getBlock().setType(Material.AIR);

                double basePrice = basePrices.get(type);
                Random rand = new Random();
                double finalPrice = basePrice + ((rand.nextDouble() * 1.0) - 0.5);
                if (finalPrice < 0.1) finalPrice = 0.1;
                if (finalPrice > 8.0) finalPrice = 8.0; // Maksimum 8'e kadar çıkabilir

                boolean isVipMaden = player.getWorld().getName().toLowerCase().contains("vip");
                if (isVipMaden || player.hasPermission("maden.vip")) {
                    finalPrice *= 2.0;
                }

                // Vault ekonomisine parayı ekle
                if (economy != null) {
                    economy.depositPlayer(player, finalPrice);
                }

                // Sadece kazan oyuncuya özel yeşil renkli actionbar bildirimi
                String formattedPrice = String.format(Locale.US, "%.2f", finalPrice);
                sendActionBar(player, ChatColor.GREEN + "+" + formattedPrice + " TL (" + type.name() + " satıldı!)");
            }
        }
    }

    private void openMadenMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, ChatColor.DARK_GREEN + "Maden Bölgeleri");
        int slot = 10;
        for (String madenName : registeredMadens) {
            if (slot > 16) slot = 19;
            ItemStack item = new ItemStack(Material.DIAMOND_ORE);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.GOLD + madenName.toUpperCase() + " MADENİ");
                item.setItemMeta(meta);
            }
            inv.setItem(slot++, item);
        }
        player.openInventory(inv);
    }

    private void openMadenListMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, ChatColor.DARK_PURPLE + "Maden Yönetimi");
        int slot = 0;
        for (String madenName : registeredMadens) {
            ItemStack item = new ItemStack(Material.STONE_PICKAXE);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) meta.setDisplayName(ChatColor.YELLOW + madenName);
            item.setItemMeta(meta);
            inv.setItem(slot++, item);
        }
        player.openInventory(inv);
    }

    private void openMadenDetailEditMenu(Player player, String madenName) {
        Inventory inv = Bukkit.createInventory(null, 27, ChatColor.DARK_BLUE + "Düzenle: " + madenName);
        // İstediğin madenler, lapa_lazuli ve kuvars dahil edildi
        Material[] materials = {
            Material.DIAMOND_ORE, 
            Material.GOLD_ORE, 
            Material.EMERALD_ORE, 
            Material.IRON_ORE, 
            Material.LAPIS_ORE, 
            Material.NETHER_QUARTZ_ORE, 
            Material.REDSTONE_ORE, 
            Material.COAL_ORE
        };
        Map<Material, Integer> oranlar = madenOranlari.getOrDefault(madenName, new HashMap<>());
        for (int i = 0; i < materials.length; i++) {
            Material mat = materials[i];
            int miktar = oranlar.getOrDefault(mat, 0);
            ItemStack item = new ItemStack(mat);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.YELLOW + mat.name());
                meta.setLore(Collections.singletonList(ChatColor.AQUA + "Miktar: " + miktar));
                item.setItemMeta(meta);
            }
            inv.setItem(i, item);
        }
        player.openInventory(inv);
    }

    private void openIrpMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 54, ChatColor.DARK_RED + "Ölüm Kayıtları (İRP)");
        player.openInventory(inv);
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!creatingMaden.containsKey(player.getUniqueId())) return;
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType() == Material.DIAMOND_AXE) {
            Block clicked = event.getClickedBlock();
            if (clicked == null) return;
            event.setCancelled(true);
            Location[] points = selectionPoints.getOrDefault(player.getUniqueId(), new Location[2]);
            if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
                points[0] = clicked.getLocation();
                player.sendMessage(ChatColor.GREEN + "1. Nokta seçildi.");
            } else {
                points[1] = clicked.getLocation();
                player.sendMessage(ChatColor.GREEN + "2. Nokta seçildi.");
            }
            selectionPoints.put(player.getUniqueId(), points);
            if (points[0] != null && points[1] != null) {
                String name = creatingMaden.get(player.getUniqueId());
                registeredMadens.add(name);

                Location p1 = points[0];
                Location p2 = points[1];
                int minX = Math.min(p1.getBlockX(), p2.getBlockX());
                int maxX = Math.max(p1.getBlockX(), p2.getBlockX());
                int minY = Math.min(p1.getBlockY(), p2.getBlockY());
                int maxY = Math.max(p1.getBlockY(), p2.getBlockY());
                int minZ = Math.min(p1.getBlockZ(), p2.getBlockZ());
                int maxZ = Math.max(p1.getBlockZ(), p2.getBlockZ());

                List<Block> blocks = new ArrayList<>();
                for (int x = minX; x <= maxX; x++) {
                    for (int y = minY; y <= maxY; y++) {
                        for (int z = minZ; z <= maxZ; z++) {
                            Block b = p1.getWorld().getBlockAt(x, y, z);
                            blocks.add(b);
                            b.setType(Material.AIR); // Alan seçilip oluşturulduğunda bloklar kırılıp yok olur
                        }
                    }
                }
                madenBloklari.put(name, blocks);

                player.sendMessage(ChatColor.GOLD + "'" + name + "' madeni oluşturuldu ve alan temizlendi! /maden edit " + name + " ile miktar girip /maden " + name + " dagit yapabilirsin.");
                creatingMaden.remove(player.getUniqueId());
                selectionPoints.remove(player.getUniqueId());
            }
        }
    }

    // ENVANTER TIKLAMA KONTROLÜ: Eşyalar ASLA envantere alınamaz, sol tık ile chate miktar yönlendirilir.
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        String title = event.getView().getTitle();

        if (title.startsWith(ChatColor.DARK_BLUE + "Düzenle: ")) {
            event.setCancelled(true); // Eşyanın envantere alınmasını Kesinlikle Engeller!
            
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || clicked.getType() == Material.AIR) return;

            String madenName = ChatColor.stripColor(title.replace("Düzenle: ", ""));
            Material clickedMat = clicked.getType();

            player.closeInventory();
            editingChatInput.add(player.getUniqueId());
            editingTargetMaden.put(player.getUniqueId(), madenName);
            editingTargetBlock.put(player.getUniqueId(), clickedMat);

            player.sendMessage(ChatColor.YELLOW + "Lütfen chat kısmına " + clickedMat.name() + " için 0 ile 100 arasında bir miktar girin:");
            return;
        }

        if (title.contains("İncele") || title.contains("Maden Bölgeleri") || title.contains("Maden Yönetimi")) {
            event.setCancelled(true);
        }
    }

    // Sohbet Üzerinden 0-100 Arası Miktar Alma ve Menüye Geri Döndürme
    @EventHandler
    public void onPlayerChat(org.bukkit.event.player.AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (editingChatInput.contains(player.getUniqueId())) {
            event.setCancelled(true);
            editingChatInput.remove(player.getUniqueId());

            String madenName = editingTargetMaden.remove(player.getUniqueId());
            Material blockMat = editingTargetBlock.remove(player.getUniqueId());

            try {
                int miktar = Integer.parseInt(event.getMessage());
                if (miktar < 0 || miktar > 100) {
                    player.sendMessage(ChatColor.RED + "Miktar 0 ile 100 arasında olmalıdır!");
                } else {
                    madenOranlari.putIfAbsent(madenName, new HashMap<>());
                    madenOranlari.get(madenName).put(blockMat, miktar);
                    player.sendMessage(ChatColor.GREEN + blockMat.name() + " için miktar " + miktar + " olarak ayarlandı!");
                }
            } catch (NumberFormatException e) {
                player.sendMessage(ChatColor.RED + "Geçersiz sayı girdiniz!");
            }

            // İşlem bittikten sonra oyuncuyu tekrar edit menüsüne geri atıyoruz
            Bukkit.getScheduler().runTask(this, () -> openMadenDetailEditMenu(player, madenName));
        }
    }

    @EventHandler
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (combatMap.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "Savaştayken komut kullanamazsın!");
        }
    }

    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Player && event.getDamager() instanceof Player) {
            Player victim = (Player) event.getEntity();
            Player attacker = (Player) event.getDamager();
            if (!victim.equals(attacker)) {
                combatMap.put(victim.getUniqueId(), attacker.getUniqueId());
            }
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        victim.getWorld().strikeLightningEffect(victim.getLocation());
    }
}
