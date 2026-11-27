package com.example.madenplugin;

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
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;

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
    private final Map<String, Location[]> madenSinirlari = new HashMap<>();
    
    private final Map<String, BukkitRunnable> activeMadenTimers = new HashMap<>();
    private final Map<String, Integer> madenKalanSureler = new HashMap<>();

    private final Map<Material, Double> basePrices = new HashMap<>();
    private final Map<UUID, Boolean> autoSellMap = new HashMap<>();
    private final Map<UUID, Integer> killStreaks = new HashMap<>();

    private final Set<UUID> editingChatInput = new HashSet<>();
    private final Map<UUID, String> editingTargetMaden = new HashMap<>();
    private final Map<UUID, Material> editingTargetBlock = new HashMap<>();

    private Scoreboard healthScoreboard;

    @Override
    public void onEnable() {
        basePrices.put(Material.DIAMOND_ORE, 4.5);
        basePrices.put(Material.EMERALD_ORE, 6.0);
        basePrices.put(Material.GOLD_ORE, 3.5);
        basePrices.put(Material.IRON_ORE, 2.5);
        basePrices.put(Material.LAPIS_ORE, 2.0);
        basePrices.put(Material.NETHER_QUARTZ_ORE, 1.8);
        basePrices.put(Material.REDSTONE_ORE, 1.5);
        basePrices.put(Material.COAL_ORE, 1.0);

        setupHealthScoreboard();
        startPlayerLocationCheckTask();

        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("maden")).setExecutor(this);
        Objects.requireNonNull(getCommand("maden")).setTabCompleter(this);
        Objects.requireNonNull(getCommand("irp")).setExecutor(this);
        Objects.requireNonNull(getCommand("sat")).setExecutor(this);
        
        getLogger().info("MadenPlugin başarıyla aktif edildi!");
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

    // Tam 1 saniyede (20 tick) bir çalışarak takılmadan 20 -> 19 -> 18 -> 17 şeklinde akıcı gösterir
    private void startPlayerLocationCheckTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (madenKalanSureler.isEmpty()) return;

                for (Player player : Bukkit.getOnlinePlayers()) {
                    Location loc = player.getLocation();
                    String currentMaden = null;

                    for (String madenName : registeredMadens) {
                        Location[] bounds = madenSinirlari.get(madenName);
                        if (bounds == null) continue;

                        Location p1 = bounds[0];
                        Location p2 = bounds[1];

                        if (loc.getWorld() != null && loc.getWorld().equals(p1.getWorld())) {
                            int x = loc.getBlockX();
                            int y = loc.getBlockY();
                            int z = loc.getBlockZ();

                            int minX = Math.min(p1.getBlockX(), p2.getBlockX());
                            int maxX = Math.max(p1.getBlockX(), p2.getBlockX());
                            int minY = Math.min(p1.getBlockY(), p2.getBlockY());
                            int maxY = Math.max(p1.getBlockY(), p2.getBlockY());
                            int minZ = Math.min(p1.getBlockZ(), p2.getBlockZ());
                            int maxZ = Math.max(p1.getBlockZ(), p2.getBlockZ());

                            if (x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ) {
                                currentMaden = madenName;
                                break;
                            }
                        }
                    }

                    if (currentMaden != null && madenKalanSureler.containsKey(currentMaden)) {
                        int kalan = madenKalanSureler.get(currentMaden);
                        String msg = ChatColor.GOLD + "" + ChatColor.BOLD + currentMaden.toUpperCase() + " MADENİ " + 
                                     ChatColor.YELLOW + "Yenilenmesine: " + ChatColor.RED + kalan + "s";
                        sendActionBar(player, msg);
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
        killStreaks.remove(p.getUniqueId());
        combatMap.remove(p.getUniqueId());
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
                madenKalanSureler.put(madenAdi, kalanSure);
                if (kalanSure <= 0) {
                    fillMadenRandomly(madenAdi);
                    kalanSure = madenSureleri.getOrDefault(madenAdi, 30);
                } else {
                    kalanSure--;
                }
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
            int roll = random.nextInt(100) + 1;
            int currentSum = 0;
            Material chosenMat = null;
            for (Map.Entry<Material, Integer> entry : oranlar.entrySet()) {
                currentSum += entry.getValue();
                if (roll <= currentSum) {
                    chosenMat = entry.getKey();
                    break;
                }
            }
            if (chosenMat != null) {
                b.setType(chosenMat);
            } else {
                b.setType(oranlar.keySet().iterator().next());
            }
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
            player.sendMessage(ChatColor.GREEN + "Maden seçim baltası verildi! Köşelere sağ ve sol tıkla.");
            return true;
        }

        if (args[0].equalsIgnoreCase("sil")) {
            if (!player.hasPermission("maden.admin")) return true;
            if (args.length < 2) {
                player.sendMessage(ChatColor.RED + "Kullanım: /maden sil <isim>");
                return true;
            }
            String madenName = args[1].toLowerCase();
            if (!registeredMadens.contains(madenName)) {
                player.sendMessage(ChatColor.RED + "Böyle bir maden bulunamadı!");
                return true;
            }
            registeredMadens.remove(madenName);
            madenOranlari.remove(madenName);
            madenBloklari.remove(madenName);
            madenSinirlari.remove(madenName);
            madenSureleri.remove(madenName);
            madenKalanSureler.remove(madenName);
            if (activeMadenTimers.containsKey(madenName)) {
                activeMadenTimers.get(madenName).cancel();
                activeMadenTimers.remove(madenName);
            }
            player.sendMessage(ChatColor.RED + "'" + madenName + "' madeni başarıyla silindi!");
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
                player.sendMessage(ChatColor.RED + "Kullanım: /maden dagit <isim>");
                return true;
            }
            String madenName = args[1].toLowerCase();
            if (!registeredMadens.contains(madenName)) {
                player.sendMessage(ChatColor.RED + "Böyle bir maden bulunamadı!");
                return true;
            }
            if (!madenOranlari.containsKey(madenName) || madenOranlari.get(madenName).isEmpty()) {
                player.sendMessage(ChatColor.RED + "Lütfen önce /maden edit " + madenName + " ile oranları ayarlayın!");
                return true;
            }

            fillMadenRandomly(madenName);
            player.sendMessage(ChatColor.GREEN + "'" + madenName + "' madeni boşluk kalmayacak şekilde başarıyla dağıtıldı!");
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
                return Arrays.asList("create", "edit", "sil", "dagit", "sure", "fiyat");
            } else if (args.length == 2 && (args[0].equalsIgnoreCase("edit") || args[0].equalsIgnoreCase("sure") || args[0].equalsIgnoreCase("dagit") || args[0].equalsIgnoreCase("sil"))) {
                return new ArrayList<>(registeredMadens);
            }
        }
        return null;
    }

    // Chatte hiçbir para mesajı çıkarmadan sessizce para aktarır
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
                double fluctuation = (rand.nextDouble() * 0.4) - 0.2;
                double finalPrice = basePrice + fluctuation;
                if (finalPrice < 0.1) finalPrice = 0.1;
                if (finalPrice > 8.5) finalPrice = 8.5;

                boolean isVipMaden = player.getWorld().getName().toLowerCase().contains("vip");
                if (isVipMaden || player.hasPermission("madenplugin.maden.vip+")) {
                    finalPrice *= 2.0;
                }

                String formattedPrice = String.format(Locale.US, "%.2f", finalPrice);
                
                // Sessiz mod (-s) ile oyuncunun sohbetine "Hesabınıza para geldi" yazısı gitmesini engeller
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "eco give " + player.getName() + " " + formattedPrice + " -s");

                sendActionBar(player, ChatColor.GREEN + "+" + formattedPrice + " TL");
            }
        }
    }

    // Sadece "maden" ve "vipmaden" oluşturulmuşsa menüde gösterir
    private void openMadenMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, ChatColor.DARK_GREEN + "Maden Bölgeleri");

        if (registeredMadens.contains("maden")) {
            ItemStack normalMaden = new ItemStack(Material.IRON_PICKAXE);
            ItemMeta meta = normalMaden.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.GREEN + "" + ChatColor.BOLD + "NORMAL MADEN");
                meta.setLore(Collections.singletonList(ChatColor.GRAY + "Işınlanmak için tıkla (/warp maden)"));
                normalMaden.setItemMeta(meta);
            }
            inv.setItem(11, normalMaden);
        }

        if (registeredMadens.contains("vipmaden")) {
            ItemStack vipMaden = new ItemStack(Material.DIAMOND_PICKAXE);
            ItemMeta meta = vipMaden.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.AQUA + "" + ChatColor.BOLD + "VIP MADEN");
                meta.setLore(Arrays.asList(
                    ChatColor.GRAY + "Işınlanmak için tıkla (/warp vipmaden)",
                    ChatColor.YELLOW + "Gereksinim: VIP+ Yetkisi"
                ));
                vipMaden.setItemMeta(meta);
            }
            inv.setItem(15, vipMaden);
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
                meta.setLore(Collections.singletonList(ChatColor.AQUA + "Miktar/Oran: " + miktar));
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
                madenSinirlari.put(name, new Location[]{p1, p2});

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
                            b.setType(Material.AIR);
                        }
                    }
                }
                madenBloklari.put(name, blocks);

                player.sendMessage(ChatColor.GOLD + "'" + name + "' madeni oluşturuldu ve alan temizlendi!");
                creatingMaden.remove(player.getUniqueId());
                selectionPoints.remove(player.getUniqueId());
            }
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        String title = event.getView().getTitle();

        // /maden menüsü tıklamaları ve yetki kontrolü
        if (title.equals(ChatColor.DARK_GREEN + "Maden Bölgeleri")) {
            event.setCancelled(true);
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || clicked.getType() == Material.AIR) return;

            player.closeInventory();
            if (clicked.getType() == Material.IRON_PICKAXE) {
                player.performCommand("warp maden");
            } else if (clicked.getType() == Material.DIAMOND_PICKAXE) {
                if (player.isOp() || player.hasPermission("madenplugin.maden.vip+")) {
                    player.performCommand("warp vipmaden");
                } else {
                    player.sendMessage(ChatColor.RED + "VIP Maden'e gitmek için 'madenplugin.maden.vip+' yetkisine sahip olmalısınız!");
                }
            }
            return;
        }

        if (title.startsWith(ChatColor.DARK_BLUE + "Düzenle: ")) {
            event.setCancelled(true);
            
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

        if (title.contains("İncele") || title.contains("Maden Yönetimi")) {
            event.setCancelled(true);
        }
    }

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
            if (victim.equals(attacker)) return;

            if (!combatMap.containsKey(victim.getUniqueId())) {
                victim.sendMessage(ChatColor.RED + "" + ChatColor.BOLD + attacker.getName() + " adlı oyuncuyla çatışmaya girdin! Sakın oyundan çıkma yoksa cezalandırılacaksın!");
            }
            if (!combatMap.containsKey(attacker.getUniqueId())) {
                attacker.sendMessage(ChatColor.RED + "" + ChatColor.BOLD + victim.getName() + " adlı oyuncuyla çatışmaya girdin! Sakın oyundan çıkma yoksa cezalandırılacaksın!");
            }

            combatMap.put(victim.getUniqueId(), attacker.getUniqueId());
            combatMap.put(attacker.getUniqueId(), victim.getUniqueId());

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
            bossBar = Bukkit.createBossBar(ChatColor.RED + "Savaşta!", BarColor.RED, BarStyle.SOLID);
            bossBar.addPlayer(player);
            combatBossBars.put(uuid, bossBar);
        }

        final double[] timeLeft = {10.0};
        BossBar finalBossBar = bossBar;

        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                if (timeLeft[0] <= 0) {
                    finalBossBar.removeAll();
                    combatBossBars.remove(uuid);
                    combatMap.remove(uuid);
                    player.sendMessage(ChatColor.GREEN + "Artık çatışmada değilsin, komut yazabilirsin.");
                    this.cancel();
                    return;
                }
                String formattedTime = String.format(Locale.US, "%.1f", timeLeft[0]);
                finalBossBar.setTitle(ChatColor.YELLOW + "Savaşta! Kalan: " + ChatColor.RED + formattedTime + "s");
                finalBossBar.setProgress(Math.max(0.0, Math.min(1.0, timeLeft[0] / 10.0)));
                timeLeft[0] -= 0.5;
            }
        };
        combatTasks.put(uuid, task);
        task.runTaskTimer(this, 0L, 10L);
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        UUID victimUuid = victim.getUniqueId();

        killStreaks.put(victimUuid, 0);
        combatMap.remove(victimUuid);
        if (combatTasks.containsKey(victimUuid)) {
            combatTasks.get(victimUuid).cancel();
            combatTasks.remove(victimUuid);
        }
        if (combatBossBars.containsKey(victimUuid)) {
            combatBossBars.get(victimUuid).removeAll();
            combatBossBars.remove(victimUuid);
        }

        victim.getWorld().strikeLightningEffect(victim.getLocation());

        Player attacker = victim.getKiller();
        if (attacker != null && !attacker.equals(victim)) {
            UUID attackerUuid = attacker.getUniqueId();
            int currentStreak = killStreaks.getOrDefault(attackerUuid, 0) + 1;
            killStreaks.put(attackerUuid, currentStreak);

            attacker.sendTitle(
                ChatColor.DARK_RED + "" + ChatColor.BOLD + victim.getName() + " ÖLDÜRÜLDÜ!",
                ChatColor.YELLOW + "Öldürme Serisi: " + ChatColor.GREEN + currentStreak,
                10, 27, 10
            );
        }
    }
}
