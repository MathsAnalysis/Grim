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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class ChunkSectionCache {

    private record Entry(Chunk_v1_18 section, AtomicInteger refs) { }

    private record SharedRef(BaseChunk section, long key) { }

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

            for (long word : storage.getData()) {
                hash ^= word;
                hash *= 0x100000001b3L;
            }
        }

        return hash == 0 ? 1 : hash;
    }

    private static boolean sectionsEqual(Chunk_v1_18 a, Chunk_v1_18 b) {
        if (a == b) return true;

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

        if (existing == null) {
            // Corrupting the player's empty chunk is actually quite meaningless
            // You are able to set blocks inside it, and they do apply, it just always returns air despite what its data says
            // So go ahead, corrupt the player's empty chunk and make it no longer all air, it doesn't matter
            //
            // LogUtil.warn("Invalid non-ground up continuous sent for empty chunk " + chunkX + " " + chunkZ + " for " + player.user.getProfile().getName() + "! This corrupts the player's empty chunk!");
            return;
        }

        BaseChunk[] current = existing.chunks();
        long[] keys = existing.sectionKeys();
        boolean share = keys != null && isSharingEnabled();
        int length = Math.min(current.length, incoming.length);

        for (int i = 0; i < length; i++) {
            if (incoming[i] == null) continue;

            BaseChunk section = incoming[i];
            long key = 0L;

            if (share) {
                SharedRef ref = internRef(section);
                section = ref.section();
                key = ref.key();
            }

            if (keys != null) {
                if (keys[i] != 0L) release(keys[i], current[i]);
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

        for (int i = 0; i < sections.length; i++) {
            if (sections[i] == null) continue;

            SharedRef ref = internRef(sections[i]);
            sections[i] = ref.section();
            keys[i] = ref.key();
        }

        return column;
    }

    public static void releaseColumnSections(Column column) {
        BaseChunk[] sections = column.chunks();
        long[] keys = column.sectionKeys();

        if (sections == null || keys == null) return;

        for (int i = 0; i < sections.length && i < keys.length; i++) {
            if (keys[i] != 0L) {
                release(keys[i], sections[i]);
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

        BaseChunk copy = copySection(section);
        release(sectionKeys[sectionIndex], section);

        column.chunks()[sectionIndex] = copy;
        sectionKeys[sectionIndex] = 0L;

        return copy;
    }

    private static SharedRef internRef(BaseChunk fresh) {
        if (fresh.isEmpty()) return internAir(fresh);

        if (!(fresh instanceof Chunk_v1_18 chunk)) return new SharedRef(fresh, 0L);

        final SharedRef[] result = new SharedRef[1];
        entries.compute(hashSection(chunk), (hash, existing) -> {
            if (existing == null) {
                result[0] = new SharedRef(chunk, hash);
                return new Entry(chunk, new AtomicInteger(1));
            }

            if (sectionsEqual(existing.section, chunk)) {
                existing.refs.incrementAndGet();
                result[0] = new SharedRef(existing.section, hash);
                return existing;
            }

            result[0] = new SharedRef(chunk, 0L);
            return existing;
        });
        return result[0];
    }

    private static SharedRef internAir(BaseChunk fresh) {
        AtomicReference<BaseChunk> slot = airSlotFor(fresh);

        if (slot == null) return new SharedRef(fresh, 0L);

        BaseChunk canonical = slot.get();

        if (canonical == null) {
            slot.compareAndSet(null, emptySection());
            canonical = slot.get();
        }

        return new SharedRef(canonical, AIR_HASH);
    }

    private static AtomicReference<BaseChunk> airSlotFor(BaseChunk section) {
        if (section instanceof Chunk_v1_18) return AIR_V1_18;
        if (section instanceof Chunk_v1_9) return AIR_V1_9;
        return null;
    }

    private static void release(long key, BaseChunk section) {
        if (key == 0L || section == AIR_V1_18.get() || section == AIR_V1_9.get()) return;

        entries.computeIfPresent(key, (hash, existing) -> {
            if (existing.section != section) return existing;
            return existing.refs.decrementAndGet() <= 0 ? null : existing;
        });
    }
}
