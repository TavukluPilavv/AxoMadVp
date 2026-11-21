package com.example.madenplugin;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.node.Node;
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
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
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
    
    // Varsayılan madenler kaldırıldı, sadece oluşturulanlar tutulacak
    private final Set<String> registeredMadens = new HashSet<>();
    private final Map<String, Integer> madenSureleri = new HashMap<>();
    private final Map<String, Map<Material, Integer>> madenOranlari = new HashMap<>();
    private final Map<String, List<Block>> madenBloklari = new HashMap<>();
    
    private final Map<String, BukkitRunnable> activeMadenTimers = new HashMap<>();

    // Otomatik Satış ve Fiyatlar
    private final Map<Material, Double> basePrices = new HashMap<>();
    private final Map<UUID, Boolean> autoSellMap = new HashMap<>();

    // Kill Streak (Öldürme Serisi) Takibi
    private final Map<UUID, Integer> killStreaks = new HashMap<>();

    // İRP Kayıtları
    private static class DeathRecord {
        String victimName;
        ItemStack[] contents;
        ItemStack[] armor;
        long time;

        public DeathRecord(String victimName, ItemStack[] contents, ItemStack[] armor) {
            this.victimName = victimName;
            this.contents = contents;
            this.armor = armor;
            this.time = System.currentTimeMillis();
        }
    }
    private final List<DeathRecord> deathRecords = new ArrayList<>();
    private final Map<UUID, Integer> playerIrpPage = new HashMap<>();

    private final Set<UUID> editingChatInput = new HashSet<>();
    private final Map<UUID, String> editingTargetMaden = new HashMap<>();
    private final Map<UUID, Material> editingTargetBlock = new HashMap<>();

    private LuckPerms luckPerms;
    private Scoreboard healthScoreboard;

    @Override
    public void onEnable() {
        RegisteredServiceProvider<LuckPerms> provider = Bukkit.getServicesManager().getRegistration(LuckPerms.class);
        if (provider != null) {
            luckPerms = provider.getProvider();
            getLogger().info("LuckPerms başarıyla bağlandı!");
        }

        basePrices.put(Material.DIAMOND_ORE, 2.3);
        basePrices.put(Material.EMERALD_ORE, 7.0);
        basePrices.put(Material.GOLD_ORE, 5.0);
        basePrices.put(Material.IRON_ORE, 4.0);
        basePrices.put(Material.LAPIS_ORE, 6.3);
        basePrices.put(Material.REDSTONE_ORE, 3.5);
        basePrices.put(Material.COAL_ORE, 2.0);
        basePrices.put(Material.DIAMOND, 2.3);
        basePrices.put(Material.EMERALD, 7.0);
        basePrices.put(Material.GOLD_INGOT, 5.0);
        basePrices.put(Material.IRON_INGOT, 4.0);
        basePrices.put(Material.LAPIS_LAZULI, 6.3);
        basePrices.put(Material.REDSTONE, 3.5);
        basePrices.put(Material.COAL, 2.0);

        setupHealthScoreboard();

        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("maden")).setExecutor(this);
        Objects.requireNonNull(getCommand("maden")).setTabCompleter(this);
        Objects.requireNonNull(getCommand("tagizinver")).setExecutor(this);
        Objects.requireNonNull(getCommand("tagizinver")).setTabCompleter(this);
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

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (healthScoreboard != null) {
            event.getPlayer().setScoreboard(healthScoreboard);
        }
        autoSellMap.putIfAbsent(event.getPlayer().getUniqueId(), true);
        killStreaks.putIfAbsent(event.getPlayer().getUniqueId(), 0);
    }

    private void sendActionBar(Player player, String message) {
        player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, net.md_5.bungee.api.chat.TextComponent.fromLegacyText(message));
    }

    private void startContinuousMadenTimer(String madenAdi) {
        if (activeMadenTimers.containsKey(madenAdi)) {
            activeMadenTimers.get(madenAdi).cancel();
        }

        int sureSaniye = madenSureleri.getOrDefault(madenAdi, 30);

        BukkitRunnable timerTask = new BukkitRunnable() {
            int kalanSure = sureSaniye;

            @Override
            public void run() {
                if (kalanSure <= 0) {
                    fillMadenRandomly(madenAdi);
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        sendActionBar(p, ChatColor.GREEN + "'" + madenAdi + "' madeni yenilendi ve tekrar dolduruldu!");
                    }
                    kalanSure = madenSureleri.getOrDefault(madenAdi, 30);
                } else {
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        sendActionBar(p, ChatColor.YELLOW + madenAdi + " madeninin yenilenmesine: " + ChatColor.RED + kalanSure + "s");
                    }
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
            playerIrpPage.put(p.getUniqueId(), 0);
            openIrpMenu(p, 0);
            return true;
        }

        if (command.getName().equalsIgnoreCase("tagizinver")) {
            if (!sender.hasPermission("maden.admin")) return true;
            if (args.length < 3) {
                sender.sendMessage(ChatColor.RED + "Kullanım: /tagizinver <rol> <izin> <true/false>");
                return true;
            }
            if (luckPerms == null) {
                sender.sendMessage(ChatColor.RED + "LuckPerms bulunamadı!");
                return true;
            }
            Group group = luckPerms.getGroupManager().getGroup(args[0]);
            if (group == null) {
                sender.sendMessage(ChatColor.RED + "Rol bulunamadı!");
                return true;
            }
            boolean val = Boolean.parseBoolean(args[2]);
            group.data().add(Node.builder(args[1]).value(val).build());
            luckPerms.getGroupManager().saveGroup(group);
            sender.sendMessage(ChatColor.GREEN + "İzin güncellendi.");
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
                player.sendMessage(ChatColor.RED + "Lütfen önce /maden edit " + madenName + " komutu ile blok oranlarını/miktarlarını ayarlayın!");
                return true;
            }

            fillMadenRandomly(madenName);
            player.sendMessage(ChatColor.GREEN + "'" + madenName + "' madeni ayarlanan oranlara göre başarıyla dağıtıldı!");
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
                player.sendMessage(ChatColor.GREEN + mat.name() + " baz fiyatı " + price + " olarak güncellendi!");
            } catch (Exception e) {
                player.sendMessage(ChatColor.RED + "Geçersiz malzeme adı veya sayısal fiyat!");
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
                player.sendMessage(ChatColor.GREEN + madenAdi + " madeninin yenilenme süresi " + saniye + " saniye olarak ayarlandı ve kalıcı döngüye alındı!");
                startContinuousMadenTimer(madenAdi);
            } catch (NumberFormatException e) {
                player.sendMessage(ChatColor.RED + "Geçerli bir saniye girmelisin!");
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
            } else if (args.length == 2 && args[0].equalsIgnoreCase("fiyat")) {
                List<String> mats = new ArrayList<>();
                for (Material m : basePrices.keySet()) mats.add(m.name());
                return mats;
            }
        }
        return null;
    }

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
                double finalPrice = basePrice;

                double fluctuation = (rand.nextDouble() * 1.0) - 0.5;
                finalPrice += fluctuation;
                if (finalPrice < 1.0) finalPrice = 1.0;
                if (finalPrice > 7.20) finalPrice = 7.20;

                boolean isVipMaden = player.getWorld().getName().toLowerCase().contains("vip");
                if (isVipMaden || player.hasPermission("maden.vip")) {
                    finalPrice *= 2.0;
                }

                String formattedPrice = String.format(Locale.US, "%.2f", finalPrice);
                player.sendMessage(ChatColor.GREEN + "+" + formattedPrice + " TL (" + type.name() + " otomatik satıldı!)");
            }
        }
    }

    // Oluşturulan Madenleri Dinamik Gösteren Menü
    private void openMadenMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, ChatColor.DARK_GREEN + "Maden Bölgeleri");

        int slot = 10;
        for (String madenName : registeredMadens) {
            if (slot > 16) slot = 19; // Envanter düzeni için
            ItemStack item = new ItemStack(Material.DIAMOND_ORE);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.GOLD + madenName.toUpperCase() + " MADENİ");
                meta.setLore(Arrays.asList(
                    ChatColor.GRAY + "Yenilenme Süresi: " + madenSureleri.getOrDefault(madenName, 30) + "s",
                    ChatColor.GREEN + "Tıklayarak ışınlan!"
                ));
                item.setItemMeta(meta);
            }
            inv.setItem(slot++, item);
        }

        player.openInventory(inv);
    }

    private void openMadenListMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, ChatColor.DARK_PURPLE + "Maden Bölgelerini Yönet");
        int slot = 0;
        for (String madenName : registeredMadens) {
            ItemStack item = new ItemStack(Material.STONE_PICKAXE);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.YELLOW + madenName);
                meta.setLore(Arrays.asList(ChatColor.GREEN + "Sol Tık: Oranları Düzenle", ChatColor.RED + "Sağ Tık: Madeni Sil"));
                item.setItemMeta(meta);
            }
            inv.setItem(slot++, item);
        }
        player.openInventory(inv);
    }

    private void openMadenDetailEditMenu(Player player, String madenName) {
        Inventory inv = Bukkit.createInventory(null, 27, ChatColor.DARK_BLUE + "Düzenle: " + madenName);
        Material[] materials = {Material.DIAMOND_ORE, Material.GOLD_ORE, Material.EMERALD_ORE, Material.IRON_ORE, Material.COAL_ORE, Material.REDSTONE_ORE, Material.LAPIS_ORE};
        Map<Material, Integer> oranlar = madenOranlari.getOrDefault(madenName, new HashMap<>());

        for (int i = 0; i < materials.length; i++) {
            Material mat = materials[i];
            int oran = oranlar.getOrDefault(mat, 0);
            ItemStack item = new ItemStack(mat);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.YELLOW + mat.name());
                meta.setLore(Arrays.asList(ChatColor.AQUA + "Mevcut Miktar/Oran: " + oran, ChatColor.GREEN + "Sağ Tık: Miktar Değiştir (0-100)"));
                item.setItemMeta(meta);
            }
            inv.setItem(i, item);
        }

        ItemStack backButton = new ItemStack(Material.LIME_CONCRETE);
        ItemMeta backMeta = backButton.getItemMeta();
        if (backMeta != null) {
            backMeta.setDisplayName(ChatColor.GREEN + "KAYDET VE GERİ DÖN");
            backButton.setItemMeta(backMeta);
        }
        inv.setItem(26, backButton);
        player.openInventory(inv);
    }

    private void openIrpMenu(Player player, int page) {
        Inventory inv = Bukkit.createInventory(null, 54, ChatColor.DARK_RED + "Ölen Oyuncular (İRP) - Sayfa " + (page + 1));
        SimpleDateFormat sdf = new SimpleDateFormat("dd MMMM yyyy 'saat' HH.mm", new Locale("tr", "TR"));

        int startIndex = page * 45;
        int endIndex = Math.min(startIndex + 45, deathRecords.size());

        int slot = 0;
        for (int i = deathRecords.size() - 1 - startIndex; i >= 0 && slot < 45; i--) {
            DeathRecord record = deathRecords.get(i);
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            ItemMeta meta = head.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.YELLOW + record.victimName);
                meta.setLore(Arrays.asList(
                    ChatColor.GRAY + "Ölüm Tarihi: " + ChatColor.WHITE + sdf.format(new Date(record.time)),
                    "",
                    ChatColor.GREEN + "Sol Tık: " + ChatColor.GRAY + "Envanteri İncele (Asla alınamaz)",
                    ChatColor.RED + "Shift + Sağ Tık: " + ChatColor.GRAY + "Eşyaları Oyuncuya Geri Ver"
                ));
                head.setItemMeta(meta);
            }
            inv.setItem(slot++, head);
        }

        if (page > 0) {
            ItemStack prev = new ItemStack(Material.ARROW);
            ItemMeta pMeta = prev.getItemMeta();
            if (pMeta != null) { pMeta.setDisplayName(ChatColor.YELLOW + "Önceki Sayfa"); prev.setItemMeta(pMeta); }
            inv.setItem(48, prev);
        }
        if (endIndex < deathRecords.size()) {
            ItemStack next = new ItemStack(Material.ARROW);
            ItemMeta nMeta = next.getItemMeta();
            if (nMeta != null) { nMeta.setDisplayName(ChatColor.YELLOW + "Sonraki Sayfa"); next.setItemMeta(nMeta); }
            inv.setItem(50, next);
        }

        player.openInventory(inv);
    }

    private void openInspectInventory(Player player, DeathRecord record) {
        Inventory inv = Bukkit.createInventory(null, 54, ChatColor.DARK_GRAY + "İncele: " + record.victimName);
        for (int i = 0; i < record.contents.length && i < 36; i++) inv.setItem(i, record.contents[i]);
        for (int i = 0; i < record.armor.length && (36 + i) < 41; i++) inv.setItem(36 + i, record.armor[i]);
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
                    String madenName = creatingMaden.get(player.getUniqueId());
                    registeredMadens.add(madenName);

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
                                b.setType(Material.AIR);
                            }
                        }
                    }
                    madenBloklari.put(madenName, blocks);

                    player.sendMessage(ChatColor.GREEN + "Alan seçildi! Şimdi /maden edit " + madenName + " ile miktar girip /maden " + madenName + " dagit yazabilirsin.");
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
        String title = event.getView().getTitle();

        if (title.startsWith(ChatColor.DARK_GRAY + "İncele: ")) {
            event.setCancelled(true);
            return;
        }

        if (title.startsWith(ChatColor.DARK_RED + "Ölen Oyuncular (İRP)")) {
            event.setCancelled(true);
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || !clicked.hasItemMeta()) return;

            int currentPage = playerIrpPage.getOrDefault(player.getUniqueId(), 0);

            if (event.getRawSlot() == 48 && clicked.getType() == Material.ARROW) {
                playerIrpPage.put(player.getUniqueId(), currentPage - 1);
                openIrpMenu(player, currentPage - 1);
                return;
            } else if (event.getRawSlot() == 50 && clicked.getType() == Material.ARROW) {
                playerIrpPage.put(player.getUniqueId(), currentPage + 1);
                openIrpMenu(player, currentPage + 1);
                return;
            }

            String victimName = ChatColor.stripColor(clicked.getItemMeta().getDisplayName());
            DeathRecord targetRecord = null;
            for (DeathRecord record : deathRecords) {
                if (record.victimName.equals(victimName)) {
                    targetRecord = record;
                    break;
                }
            }

            if (targetRecord != null) {
                if (event.isLeftClick() && !event.isShiftClick()) {
                    openInspectInventory(player, targetRecord);
                } else if (event.isRightClick() && event.isShiftClick()) {
                    Player target = Bukkit.getPlayer(victimName);
                    if (target != null && target.isOnline()) {
                        target.getInventory().setContents(targetRecord.contents);
                        target.getInventory().setArmorContents(targetRecord.armor);
                        player.sendMessage(ChatColor.GREEN + victimName + " adlı oyuncunun eşyaları iade edildi!");
                        target.sendMessage(ChatColor.GREEN + "Ölümündeki eşyalar envanterine geri yüklendi.");
                        player.closeInventory();
                    } else {
                        player.sendMessage(ChatColor.RED + "Oyuncu oyunda değil!");
                    }
                }
            }
        } else if (title.equals(ChatColor.DARK_GREEN + "Maden Bölgeleri")) {
            event.setCancelled(true);
            ItemStack clicked = event.getCurrentItem();
            if (clicked != null && clicked.hasItemMeta()) {
                String madenName = ChatColor.stripColor(clicked.getItemMeta().getDisplayName()).replace(" MADENİ", "").toLowerCase();
                player.closeInventory();
                player.performCommand("warp " + madenName);
            }
        } else if (title.equals(ChatColor.DARK_PURPLE + "Maden Bölgelerini Yönet")) {
            event.setCancelled(true);
            ItemStack clickedItem = event.getCurrentItem();
            if (clickedItem == null || clickedItem.getType() == Material.AIR) return;
            String madenName = ChatColor.stripColor(clickedItem.getItemMeta().getDisplayName());

            if (event.isLeftClick()) {
                openMadenDetailEditMenu(player, madenName);
            } else if (event.isRightClick()) {
                registeredMadens.remove(madenName);
                madenOranlari.remove(madenName);
                madenBloklari.remove(madenName);
                if (activeMadenTimers.containsKey(madenName)) {
                    activeMadenTimers.get(madenName).cancel();
                    activeMadenTimers.remove(madenName);
                }
                player.sendMessage(ChatColor.RED + "'" + madenName + "' silindi.");
                openMadenListMenu(player);
            }
        } else if (title.startsWith(ChatColor.DARK_BLUE + "Düzenle: ")) {
            event.setCancelled(true);
            ItemStack clickedItem = event.getCurrentItem();
            if (clickedItem == null || clickedItem.getType() == Material.AIR) return;
            String madenName = ChatColor.stripColor(title.replace("Düzenle: ", ""));

            if (event.getRawSlot() == 26) {
                player.closeInventory();
                player.sendMessage(ChatColor.GREEN + "'" + madenName + "' ayarları kaydedildi!");
                return;
            }

            if (event.isRightClick()) {
                player.closeInventory();
                editingChatInput.add(player.getUniqueId());
                editingTargetMaden.put(player.getUniqueId(), madenName);
                editingTargetBlock.put(player.getUniqueId(), clickedItem.getType());
                player.sendMessage(ChatColor.YELLOW + "Chat kısmına 0 ile 100 arasında bir miktar giriniz:");
            }
        }
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (editingChatInput.contains(player.getUniqueId())) {
            event.setCancelled(true);
            editingChatInput.remove(player.getUniqueId());
            String madenName = editingTargetMaden.remove(player.getUniqueId());
            Material blockMat = editingTargetBlock.remove(player.getUniqueId());

            try {
                int miktar = Integer.parseInt(event.getMessage());
                if (miktar < 0 || miktar > 100) {
                    player.sendMessage(ChatColor.RED + "Miktar 0-100 arasında olmalıdır!");
                } else {
                    madenOranlari.putIfAbsent(madenName, new HashMap<>());
                    madenOranlari.get(madenName).put(blockMat, miktar);
                    player.sendMessage(ChatColor.GREEN + blockMat.name() + " için miktar " + miktar + " olarak ayarlandı.");
                }
            } catch (NumberFormatException e) {
                player.sendMessage(ChatColor.RED + "Geçersiz sayı!");
            }

            Bukkit.getScheduler().runTask(this, () -> openMadenDetailEditMenu(player, madenName));
        }
    }

    @EventHandler
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (combatMap.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "Savaştasın! Savaş süresi bitmeden komut kullanamazsın!");
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
        if (combatTasks.containsKey(uuid)) combatTasks.get(uuid).cancel();

        BossBar bossBar = combatBossBars.get(uuid);
        if (bossBar == null) {
            bossBar = Bukkit.createBossBar(ChatColor.RED + "Savaşta!", BarColor.GREEN, BarStyle.SOLID);
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
                    player.sendMessage(ChatColor.GREEN + "Savaş bitti, komut yazabilirsin.");
                    this.cancel();
                    return;
                }
                String formattedTime = String.format(Locale.US, "%.1f", timeLeft[0]);
                finalBossBar.setTitle(ChatColor.YELLOW + "Savaşta! Kalan: " + formattedTime + "s");
                finalBossBar.setProgress(timeLeft[0] / 15.0);
                finalBossBar.setColor(timeLeft[0] <= 5.0 ? BarColor.RED : BarColor.GREEN);
                timeLeft[0] -= 0.1;
            }
        };
        combatTasks.put(uuid, task);
        task.runTaskTimer(this, 0L, 2L);
    }

    // Kill Streak ve Ekranın Ortasında Büyük Yazı Özelliği
    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        UUID victimUuid = victim.getUniqueId();

        // Ölen kişinin serisini sıfırla
        killStreaks.put(victimUuid, 0);

        deathRecords.add(new DeathRecord(victim.getName(), victim.getInventory().getContents(), victim.getInventory().getArmorContents()));
        victim.getWorld().strikeLightningEffect(victim.getLocation());

        UUID attackerUuid = combatMap.get(victimUuid);
        if (attackerUuid != null) {
            Player attacker = Bukkit.getPlayer(attackerUuid);
            if (attacker != null && attacker.isOnline()) {
                // Katilin serisini artır
                int streak = killStreaks.getOrDefault(attacker.getUniqueId(), 0) + 1;
                killStreaks.put(attacker.getUniqueId(), streak);

                // Ekranın ortasında büyük yazı (Title) gönder
                attacker.sendTitle(
                    ChatColor.RED + victim.getName() + " ÖLDÜRÜLDÜ!",
                    ChatColor.YELLOW + "Öldürme Serisi: " + streak,
                    10, 40, 10
                );

                ItemStack handItem = attacker.getInventory().getItemInMainHand();
                String itemName = "eli";
                if (handItem.getType() != Material.AIR) {
                    itemName = (handItem.hasItemMeta() && handItem.getItemMeta().hasDisplayName()) ? handItem.getItemMeta().getDisplayName() : handItem.getType().name().toLowerCase().replace("_", " ");
                }

                List<ItemStack> drops = new ArrayList<>(event.getDrops());
                event.getDrops().clear();
                for (ItemStack drop : drops) {
                    if (drop == null || drop.getType() == Material.AIR) continue;
                    HashMap<Integer, ItemStack> overflow = attacker.getInventory().addItem(drop);
                    for (ItemStack extra : overflow.values()) victim.getWorld().dropItemNaturally(victim.getLocation(), extra);
                }

                event.setDeathMessage(ChatColor.RED + victim.getName() + ChatColor.WHITE + " oyuncusu " + ChatColor.YELLOW + itemName + ChatColor.WHITE + " ile " + ChatColor.GREEN + attacker.getName() + ChatColor.WHITE + " tarafından öldürüldü.");
                return;
            }
        }
        event.setDeathMessage(ChatColor.RED + victim.getName() + ChatColor.WHITE + " öldürüldü.");
    }
}
