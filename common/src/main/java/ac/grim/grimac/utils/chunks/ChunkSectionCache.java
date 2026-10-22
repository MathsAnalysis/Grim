package ac.grim.grimac.utils.chunks;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.utils.latency.CompensatedWorld;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.impl.v1_16.Chunk_v1_9;
import com.github.retrooper.packetevents.protocol.world.chunk.impl.v_1_18.Chunk_v1_18;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.DataPalette;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.GlobalPalette;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.ListPalette;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.MapPalette;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.Palette;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.SingletonPalette;
import com.github.retrooper.packetevents.protocol.world.chunk.storage.BaseStorage;
import com.github.retrooper.packetevents.protocol.world.chunk.storage.BitStorage;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReference;

public final class ChunkSectionCache {

    private static final class Entry {
        final Chunk_v1_18 section;
        volatile int refs;

        Entry(Chunk_v1_18 section, int initialRefs) {
            this.section = section;
            this.refs = initialRefs;
        }
    }

    private static final AtomicIntegerFieldUpdater<Entry> REFS_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(Entry.class, "refs");

    private record SharedRef(BaseChunk section, long key) { }

    private static final class SharedRefHolder {
        BaseChunk section;
        long key;
    }

    private static final ThreadLocal<SharedRefHolder> HOLDER = ThreadLocal.withInitial(SharedRefHolder::new);

    private static final ConcurrentHashMap<Long, Entry> entries = new ConcurrentHashMap<>();

    private static final long AIR_HASH = airHash();
    private static final AtomicReference<BaseChunk> AIR_V1_18 = new AtomicReference<>();
    private static final AtomicReference<BaseChunk> AIR_V1_9 = new AtomicReference<>();

    private ChunkSectionCache() {
    }

    private static boolean isSharingEnabled() {
        return GrimAPI.INSTANCE.getConfigManager().isExperimentalChunkSharing();
    }

    private static long airHash() {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < 4096; i++) {
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static long hashSection(Chunk_v1_18 section) {
        DataPalette data = section.getChunkData();
        Palette palette = data.palette;
        long hash = 0xcbf29ce484222325L;

        hash ^= ((long) section.getBlockCount() << 32) ^ section.getFluidCount();
        hash *= 0x100000001b3L;

        int size = palette.size();
        hash ^= (size & 0xFFFFFFFFL);
        hash *= 0x100000001b3L;

        if (!(palette instanceof GlobalPalette)) {
            for (int i = 0; i < size; i++) {
                hash ^= (palette.idToState(i) & 0xFFFFFFFFL);
                hash *= 0x100000001b3L;
            }
        }

        BaseStorage storage = data.storage;
        if (storage != null) {
            hash ^= (storage.getBitsPerEntry() & 0xFFFFFFFFL);
            hash *= 0x100000001b3L;

            long[] storageData = storage.getData();
            int len = storageData.length;
            int i = 0;

            long h1 = hash;
            long h2 = 0xcbf29ce484222325L;
            int limit = len - 1;
            while (i < limit) {
                h1 = (h1 ^ storageData[i]) * 0x100000001b3L;
                h2 = (h2 ^ storageData[i + 1]) * 0x100000001b3L;
                i += 2;
            }
            if (i < len) {
                h1 = (h1 ^ storageData[i]) * 0x100000001b3L;
            }
            hash = h1 ^ (h2 * 0x100000001b3L);
        }

        return hash == 0 ? 1 : hash;
    }

    private static boolean sectionsEqual(Chunk_v1_18 a, Chunk_v1_18 b) {
        if (a == b) return true;
        if (a == null || b == null) return false;

        if (a.getBlockCount() != b.getBlockCount() || a.getFluidCount() != b.getFluidCount()) {
            return false;
        }

        if (rawPalettesEqual(a.getChunkData(), b.getChunkData())) return true;

        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    if (a.getBlockId(x, y, z) != b.getBlockId(x, y, z)) return false;
                }
            }
        }

        return true;
    }

    private static boolean rawPalettesEqual(DataPalette da, DataPalette db) {
        if (da == db) return true;
        if (da == null || db == null) return false;

        if (da.palette.size() != db.palette.size()) return false;
        if (!storagesEqual(da.storage, db.storage)) return false;

        return paletteMappingsEqual(da.palette, db.palette);
    }

    private static boolean storagesEqual(BaseStorage sa, BaseStorage sb) {
        if (sa == sb) return true;
        if (sa == null || sb == null) return false;
        if (sa.getBitsPerEntry() != sb.getBitsPerEntry()) return false;

        return Arrays.equals(sa.getData(), sb.getData());
    }

    private static boolean paletteMappingsEqual(Palette pa, Palette pb) {
        if (pa == pb) return true;

        if (pa instanceof GlobalPalette && pb instanceof GlobalPalette) return true;
        if (pa instanceof GlobalPalette || pb instanceof GlobalPalette) return false;

        int size = pa.size();
        if (size != pb.size()) return false;

        for (int i = 0; i < size; i++) {
            if (pa.idToState(i) != pb.idToState(i)) return false;
        }

        return true;
    }

    private static BaseChunk copySection(BaseChunk source) {
        if (source.isEmpty()) return emptySection();

        if (source instanceof Chunk_v1_18 chunk) {
            BaseChunk clone = cloneSection(chunk);
            if (clone != null) return clone;
        }

        BaseChunk copy = emptySection();
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    copy.set(x, y, z, source.getBlockId(x, y, z));
                }
            }
        }
        return copy;
    }

    private static BaseChunk cloneSection(Chunk_v1_18 source) {
        DataPalette data = source.getChunkData();
        Palette palette = data.palette;
        Palette paletteCopy;

        if (palette instanceof SingletonPalette || palette instanceof GlobalPalette) {
            paletteCopy = palette;
        } else if (palette instanceof ListPalette) {
            int[] states = new int[palette.size()];
            for (int i = 0; i < states.length; i++) {
                states[i] = palette.idToState(i);
            }
            paletteCopy = new ListPalette(palette.getBits(), states);
        } else if (palette instanceof MapPalette) {
            paletteCopy = new MapPalette(palette.getBits());
            int size = palette.size();
            for (int i = 0; i < size; i++) {
                paletteCopy.stateToId(palette.idToState(i));
            }
            if (paletteCopy.size() != size) return null;
        } else {
            return null;
        }

        BaseStorage storage = data.storage;
        BaseStorage storageCopy = null;

        if (storage != null) {
            if (!(storage instanceof BitStorage bitStorage)) return null;
            storageCopy = new BitStorage(bitStorage.getBitsPerEntry(), bitStorage.getSize(), bitStorage.getData().clone());
        }

        return new Chunk_v1_18(source.getBlockCount(), source.getFluidCount(), new DataPalette(paletteCopy, storageCopy, data.paletteType), null);
    }

    private static BaseChunk emptySection() {
        BaseChunk empty = CompensatedWorld.create();
        empty.set(0, 0, 0, 0);
        return empty;
    }

    public static void mergeIncomingSections(CompensatedWorld world, int chunkX, int chunkZ, BaseChunk[] incoming) {
        Column existing = world.getChunk(chunkX, chunkZ);
        if (existing == null) return;

        BaseChunk[] current = existing.chunks();
        long[] keys = existing.sectionKeys();
        boolean share = keys != null && isSharingEnabled();
        int length = Math.min(current.length, incoming.length);

        SharedRefHolder holder = share ? HOLDER.get() : null;

        for (int i = 0; i < length; i++) {
            BaseChunk section = incoming[i];
            if (section == null) continue;

            long key = 0L;

            if (share) {
                intern(section, holder);
                section = holder.section;
                key = holder.key;
            }

            if (keys != null) {
                long oldKey = keys[i];
                if (oldKey != 0L) {
                    release(oldKey, current[i]);
                }
                keys[i] = key;
            }
            current[i] = section;
        }
    }

    public static Column shareColumnSections(Column column) {
        if (!isSharingEnabled()) return column;

        BaseChunk[] sections = column.chunks();
        long[] keys = column.sectionKeys();

        if (keys == null) {
            keys = new long[sections.length];
            column = new Column(column.x(), column.z(), sections, keys, column.transaction());
        }

        SharedRefHolder holder = HOLDER.get();
        for (int i = 0; i < sections.length; i++) {
            BaseChunk section = sections[i];
            if (section == null) continue;

            intern(section, holder);
            sections[i] = holder.section;
            keys[i] = holder.key;
        }

        return column;
    }

    public static void releaseColumnSections(Column column) {
        BaseChunk[] sections = column.chunks();
        long[] keys = column.sectionKeys();
        if (sections == null || keys == null) return;

        int len = Math.min(sections.length, keys.length);
        for (int i = 0; i < len; i++) {
            long key = keys[i];
            if (key != 0L) {
                release(key, sections[i]);
                keys[i] = 0L;
            }
        }
    }

    public static void releaseSharedSections(CompensatedWorld world) {
        for (Column column : world.chunks.values()) {
            if (column != null) releaseColumnSections(column);
        }
    }

    public static BaseChunk detachSectionForWrite(Column column, int sectionIndex, BaseChunk section) {
        long[] sectionKeys = column.sectionKeys();
        if (sectionKeys == null || sectionKeys[sectionIndex] == 0L) return section;

        long key = sectionKeys[sectionIndex];
        BaseChunk copy = copySection(section);
        release(key, section);

        column.chunks()[sectionIndex] = copy;
        sectionKeys[sectionIndex] = 0L;

        return copy;
    }

    private static void intern(BaseChunk fresh, SharedRefHolder out) {
        if (fresh.isEmpty()) {
            BaseChunk canonical = getCanonicalAir(fresh);
            if (canonical != null) {
                out.section = canonical;
                out.key = AIR_HASH;
            } else {
                out.section = fresh;
                out.key = 0L;
            }
            return;
        }

        if (!(fresh instanceof Chunk_v1_18 chunk)) {
            out.section = fresh;
            out.key = 0L;
            return;
        }

        long hash = hashSection(chunk);
        Long key = hash;

        while (true) {
            Entry existing = entries.get(key);
            if (existing != null) {
                if (sectionsEqual(existing.section, chunk)) {
                    int r;
                    do {
                        r = existing.refs;
                        if (r <= 0) {
                            entries.remove(key, existing);
                            existing = null;
                            break;
                        }
                    } while (!REFS_UPDATER.compareAndSet(existing, r, r + 1));

                    if (existing != null) {
                        out.section = existing.section;
                        out.key = hash;
                        return;
                    }
                } else {
                    out.section = chunk;
                    out.key = 0L;
                    return;
                }
            }

            Entry newEntry = new Entry(chunk, 1);
            Entry prev = entries.putIfAbsent(key, newEntry);
            if (prev == null) {
                out.section = chunk;
                out.key = hash;
                return;
            }
        }
    }

    private static BaseChunk getCanonicalAir(BaseChunk section) {
        if (section instanceof Chunk_v1_18) {
            BaseChunk air = AIR_V1_18.get();
            if (air == null) {
                air = emptySection();
                if (!AIR_V1_18.compareAndSet(null, air)) {
                    air = AIR_V1_18.get();
                }
            }
            return air;
        }
        if (section instanceof Chunk_v1_9) {
            BaseChunk air = AIR_V1_9.get();
            if (air == null) {
                air = emptySection();
                if (!AIR_V1_9.compareAndSet(null, air)) {
                    air = AIR_V1_9.get();
                }
            }
            return air;
        }
        return null;
    }

    private static void release(long key, BaseChunk section) {
        if (key == 0L || key == AIR_HASH) return;

        Long boxedKey = key;
        Entry existing = entries.get(boxedKey);
        if (existing == null || existing.section != section) return;

        int newRefs = REFS_UPDATER.decrementAndGet(existing);
        if (newRefs <= 0) {
            entries.remove(boxedKey, existing);
        }
    }

    private static SharedRef internRef(BaseChunk fresh) {
        SharedRefHolder holder = HOLDER.get();
        intern(fresh, holder);
        return new SharedRef(holder.section, holder.key);
    }
}
