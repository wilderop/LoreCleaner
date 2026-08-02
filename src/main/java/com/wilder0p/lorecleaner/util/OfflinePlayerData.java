package com.wilder0p.lorecleaner.util;

import com.wilder0p.lorecleaner.LoreCleanerPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

public class OfflinePlayerData {

    private final LoreCleanerPlugin plugin;
    private final UUID uuid;
    private final File datFile;

    private Object rootCompound;
    private boolean dirty = false;
    private boolean hadConversionFailures = false;

    private OfflinePlayerData(LoreCleanerPlugin plugin, UUID uuid, File datFile, Object rootCompound) {
        this.plugin = plugin;
        this.uuid = uuid;
        this.datFile = datFile;
        this.rootCompound = rootCompound;
    }

    private static volatile boolean loggedFirstReadError = false;
    private static volatile boolean loggedPathDiagnostic = false;

    public enum LoadStatus {
        OK,
        FILE_MISSING,
        READ_ERROR
    }

    public static final class LoadResult {
        public final OfflinePlayerData data;
        public final LoadStatus status;
        public final String detail;

        private LoadResult(OfflinePlayerData data, LoadStatus status, String detail) {
            this.data = data;
            this.status = status;
            this.detail = detail;
        }

        public static LoadResult ok(OfflinePlayerData data) {
            return new LoadResult(data, LoadStatus.OK, null);
        }

        public static LoadResult missing(String detail) {
            return new LoadResult(null, LoadStatus.FILE_MISSING, detail);
        }

        public static LoadResult error(String detail) {
            return new LoadResult(null, LoadStatus.READ_ERROR, detail);
        }
    }

    public static OfflinePlayerData load(LoreCleanerPlugin plugin, UUID uuid) {
        return loadDetailed(plugin, uuid).data;
    }

    public static LoadResult loadDetailed(LoreCleanerPlugin plugin, UUID uuid) {
        File datFile = findPlayerDat(uuid);
        if (datFile == null) {
            logPathDiagnosticOnce(plugin);
            return LoadResult.missing("no " + uuid + ".dat under players/data or playerdata");
        }

        try {
            Object compound = readCompressed(datFile);
            if (compound == null) {
                return LoadResult.error("readCompressed returned null for " + datFile.getAbsolutePath());
            }
            return LoadResult.ok(new OfflinePlayerData(plugin, uuid, datFile, compound));
        } catch (Exception e) {
            if (!loggedFirstReadError) {
                loggedFirstReadError = true;
                plugin.getLogger().log(Level.SEVERE,
                        "FIRST playerdata read failure (further failures will be quiet). "
                                + "File: " + datFile.getAbsolutePath()
                                + " size=" + datFile.length() + "B",
                        e);
            }
            return LoadResult.error(e.getClass().getSimpleName() + ": " + e.getMessage()
                    + " @ " + datFile.getAbsolutePath());
        }
    }

    private static final String[] PLAYERDATA_SUBDIRS = {
            "players/data",
            "playerdata"
    };

    public static File findPlayerDat(UUID uuid) {
        String name = uuid.toString() + ".dat";

        for (World world : Bukkit.getWorlds()) {
            File found = findInWorldFolder(world.getWorldFolder(), name);
            if (found != null) return found;
        }

        File container = Bukkit.getWorldContainer();
        File[] children = container.listFiles();
        if (children != null) {
            for (File worldFolder : children) {
                if (!worldFolder.isDirectory()) continue;
                File found = findInWorldFolder(worldFolder, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static File findInWorldFolder(File worldFolder, String datName) {
        for (String sub : PLAYERDATA_SUBDIRS) {
            File f = new File(new File(worldFolder, sub), datName);
            if (f.isFile()) return f;
        }
        return null;
    }

    private static void logPathDiagnosticOnce(LoreCleanerPlugin plugin) {
        if (loggedPathDiagnostic) return;
        loggedPathDiagnostic = true;

        StringBuilder sb = new StringBuilder();
        sb.append("playerdata path diagnostic (logged once):\n");
        for (World world : Bukkit.getWorlds()) {
            for (String sub : PLAYERDATA_SUBDIRS) {
                File dir = new File(world.getWorldFolder(), sub);
                File[] files = dir.isDirectory()
                        ? dir.listFiles((d, n) -> n.endsWith(".dat") && !n.endsWith(".dat_old"))
                        : null;
                int count = files == null ? 0 : files.length;
                sb.append("  world '").append(world.getName()).append("' /").append(sub)
                        .append(" -> ").append(dir.getAbsolutePath())
                        .append(" exists=").append(dir.isDirectory())
                        .append(" datCount=").append(count).append('\n');
            }
        }
        plugin.getLogger().warning(sb.toString());
    }

    public boolean hadConversionFailures() {
        return hadConversionFailures;
    }

    public Location getLogoutLocation() {
        try {
            Object posList = getList(rootCompound, "Pos");
            if (posList == null) return null;

            int size = listSize(posList);
            if (size < 3) return null;

            double x = listGetDouble(posList, 0);
            double y = listGetDouble(posList, 1);
            double z = listGetDouble(posList, 2);

            World world = resolveWorld();
            if (world == null) {
                world = Bukkit.getWorlds().get(0);
            }
            return new Location(world, x, y, z);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Could not read Pos for " + uuid, e);
            return null;
        }
    }

    private World resolveWorld() {
        try {
            String dim = compoundGetString(rootCompound, "Dimension");
            if (dim != null && !dim.isEmpty()) {
                for (World w : Bukkit.getWorlds()) {
                    if (w.getKey().toString().equals(dim)
                            || w.getName().equals(dim)
                            || ("minecraft:" + w.getName()).equals(dim)) {
                        return w;
                    }
                }
            }
        } catch (Exception ignored) {}

        try {
            Method getInt = rootCompound.getClass().getMethod("getInt", String.class);
            Object result = getInt.invoke(rootCompound, "Dimension");
            if (result instanceof Number n) {
                int id = n.intValue();
                if (id == -1) {
                    for (World w : Bukkit.getWorlds()) {
                        if (w.getEnvironment() == World.Environment.NETHER) return w;
                    }
                } else if (id == 1) {
                    for (World w : Bukkit.getWorlds()) {
                        if (w.getEnvironment() == World.Environment.THE_END) return w;
                    }
                }
            }
        } catch (Exception ignored) {}

        return null;
    }

    public List<ItemStack> scanLoreItems() {
        List<ItemStack> result = new ArrayList<>();
        result.addAll(scanList("Inventory"));
        result.addAll(scanList("EnderItems"));
        return result;
    }

    public List<ItemStack> extractAndRemoveLoreItems() {
        List<ItemStack> result = new ArrayList<>();
        result.addAll(extractAndRemoveFromList("Inventory"));
        result.addAll(extractAndRemoveFromList("EnderItems"));
        return result;
    }

    private List<ItemStack> scanList(String listKey) {
        List<ItemStack> result = new ArrayList<>();
        try {
            Object list = getList(rootCompound, listKey);
            if (list == null) return result;

            int size = listSize(list);
            for (int i = 0; i < size; i++) {
                Object itemCompound = listGetCompound(list, i);
                if (itemCompound == null) continue;
                ItemStack stack = nbtToItemStack(itemCompound);
                if (stack == null) {
                    hadConversionFailures = true;
                    continue;
                }
                if (hasLore(stack)) {
                    result.add(stack);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error scanning " + listKey + " for " + uuid, e);
            hadConversionFailures = true;
        }
        return result;
    }

    private List<ItemStack> extractAndRemoveFromList(String listKey) {
        List<ItemStack> result = new ArrayList<>();
        try {
            Object list = getList(rootCompound, listKey);
            if (list == null) return result;

            int size = listSize(list);
            List<Integer> toRemove = new ArrayList<>();

            for (int i = 0; i < size; i++) {
                Object itemCompound = listGetCompound(list, i);
                if (itemCompound == null) continue;
                ItemStack stack = nbtToItemStack(itemCompound);
                if (stack == null) {
                    hadConversionFailures = true;
                    continue;
                }
                if (hasLore(stack)) {
                    result.add(stack);
                    toRemove.add(i);
                }
            }

            for (int i = toRemove.size() - 1; i >= 0; i--) {
                listRemove(list, toRemove.get(i));
            }

            if (!toRemove.isEmpty()) {
                dirty = true;
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error extracting " + listKey + " for " + uuid, e);
            hadConversionFailures = true;
        }
        return result;
    }

    private boolean hasLore(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return false;
        ItemMeta meta = stack.getItemMeta();
        return meta != null && meta.hasLore();
    }

    public void save() {
        if (!dirty) return;
        try {
            File bak = new File(datFile.getAbsolutePath() + ".lorecleaner.bak");
            if (datFile.exists()) {
                Files.copy(datFile.toPath(), bak.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }

            File tmp = new File(datFile.getAbsolutePath() + ".tmp");
            writeCompressed(tmp, rootCompound);

            Files.move(tmp.toPath(), datFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);

            dirty = false;
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save playerdata for " + uuid, e);
            throw new RuntimeException("playerdata save failed for " + uuid, e);
        }
    }

    private static Object readCompressed(File file) throws Exception {
        Class<?> nbtIo = Class.forName("net.minecraft.nbt.NbtIo");
        Class<?> nbtAccounter = Class.forName("net.minecraft.nbt.NbtAccounter");

        Method unlimitedHeap = nbtAccounter.getMethod("unlimitedHeap");
        Object accounter = unlimitedHeap.invoke(null);

        try {
            Method readPath = nbtIo.getMethod("readCompressed",
                    java.nio.file.Path.class, nbtAccounter);
            return readPath.invoke(null, file.toPath(), accounter);
        } catch (NoSuchMethodException ignored) {
        }

        Method readStream = nbtIo.getMethod("readCompressed",
                java.io.InputStream.class, nbtAccounter);
        try (FileInputStream fis = new FileInputStream(file)) {
            return readStream.invoke(null, fis, accounter);
        }
    }

    private static void writeCompressed(File file, Object compound) throws Exception {
        Class<?> nbtIo = Class.forName("net.minecraft.nbt.NbtIo");
        Method writeCompressed = nbtIo.getMethod("writeCompressed",
                Class.forName("net.minecraft.nbt.CompoundTag"), java.io.OutputStream.class);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            writeCompressed.invoke(null, compound, fos);
        }
    }

    private Object getList(Object compound, String key) throws Exception {
        try {
            Method m = compound.getClass().getMethod("getList", String.class);
            Object result = m.invoke(compound, key);
            if (result instanceof Optional<?> opt) {
                return opt.orElse(null);
            }
            return result;
        } catch (NoSuchMethodException e) {
            Method m = compound.getClass().getMethod("getList", String.class, int.class);
            return m.invoke(compound, key, 10);
        }
    }

    private String compoundGetString(Object compound, String key) throws Exception {
        try {
            Method m = compound.getClass().getMethod("getString", String.class);
            Object result = m.invoke(compound, key);
            if (result instanceof Optional<?> opt) {
                return opt.map(Object::toString).orElse(null);
            }
            return result != null ? result.toString() : null;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private int listSize(Object list) throws Exception {
        Method m = list.getClass().getMethod("size");
        return ((Number) m.invoke(list)).intValue();
    }

    private Object listGetCompound(Object list, int index) throws Exception {
        try {
            Method m = list.getClass().getMethod("getCompound", int.class);
            Object result = m.invoke(list, index);
            if (result instanceof Optional<?> opt) {
                return opt.orElse(null);
            }
            return result;
        } catch (NoSuchMethodException e) {
            Method m = list.getClass().getMethod("get", int.class);
            return m.invoke(list, index);
        }
    }

    private double listGetDouble(Object list, int index) throws Exception {
        try {
            Method m = list.getClass().getMethod("getDouble", int.class);
            return ((Number) m.invoke(list, index)).doubleValue();
        } catch (NoSuchMethodException e) {
            Method m = list.getClass().getMethod("getDoubleOr", int.class, double.class);
            return ((Number) m.invoke(list, index, 0.0)).doubleValue();
        }
    }

    private void listRemove(Object list, int index) throws Exception {
        Method m = list.getClass().getMethod("remove", int.class);
        m.invoke(list, index);
    }

    private ItemStack nbtToItemStack(Object itemCompound) {
        try {
            Class<?> nmsItemStack = Class.forName("net.minecraft.world.item.ItemStack");
            Class<?> craftItemStack = Class.forName("org.bukkit.craftbukkit.inventory.CraftItemStack");
            Class<?> compoundTagClass = Class.forName("net.minecraft.nbt.CompoundTag");

            Class<?> craftRegistry = Class.forName("org.bukkit.craftbukkit.CraftRegistry");
            Object registryAccess = craftRegistry.getMethod("getMinecraftRegistry").invoke(null);

            try {
                Method parseOptional = nmsItemStack.getMethod("parseOptional",
                        Class.forName("net.minecraft.core.HolderLookup$Provider"),
                        compoundTagClass);
                Object nmsStack = parseOptional.invoke(null, registryAccess, itemCompound);
                if (nmsStack == null) return null;

                Method isEmpty = nmsItemStack.getMethod("isEmpty");
                if (Boolean.TRUE.equals(isEmpty.invoke(nmsStack))) return null;

                Method asBukkitCopy = craftItemStack.getMethod("asBukkitCopy", nmsItemStack);
                return (ItemStack) asBukkitCopy.invoke(null, nmsStack);
            } catch (NoSuchMethodException ignored) {
            }

            Object codec = nmsItemStack.getField("CODEC").get(null);
            Class<?> nbtOps = Class.forName("net.minecraft.nbt.NbtOps");
            Object ops = nbtOps.getField("INSTANCE").get(null);

            Method createContext = registryAccess.getClass()
                    .getMethod("createSerializationContext", Class.forName("com.mojang.serialization.DynamicOps"));
            Object context = createContext.invoke(registryAccess, ops);

            Method parse = codec.getClass().getMethod("parse",
                    Class.forName("com.mojang.serialization.DynamicOps"), Object.class);
            Object dataResult = parse.invoke(codec, context, itemCompound);

            Object nmsStack;
            try {
                Method resultOrPartial = dataResult.getClass().getMethod("resultOrPartial");
                Object opt = resultOrPartial.invoke(dataResult);
                if (opt instanceof Optional<?> o) {
                    nmsStack = o.orElse(null);
                } else {
                    nmsStack = opt;
                }
            } catch (NoSuchMethodException e) {
                Method getOrThrow = dataResult.getClass().getMethod("getOrThrow");
                nmsStack = getOrThrow.invoke(dataResult);
            }

            if (nmsStack == null) return null;

            Method asBukkitCopy = craftItemStack.getMethod("asBukkitCopy", nmsItemStack);
            return (ItemStack) asBukkitCopy.invoke(null, nmsStack);

        } catch (Exception e) {
            plugin.getLogger().log(Level.FINE, "NBT → ItemStack conversion failed for one item of " + uuid, e);
            hadConversionFailures = true;
            return null;
        }
    }
}
