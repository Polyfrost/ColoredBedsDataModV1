package org.polyfrost.coloredbedsdata;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BedScanner {

    public record Bed(int x, int z, String color) {}

    private static final double CLEAR_SCORE = 0.5;

    public record Result(List<BlockPos> blocks, String color, double score) {}

    private static final int CHUNK_RADIUS = 16;
    private static final int NEARBY_XZ = 40;
    private static final int NEARBY_Y = 16;
    private static final Set<String> COLORED_SUFFIXES = Set.of(
            "wool", "carpet", "terracotta", "stained_glass", "stained_glass_pane", "concrete", "concrete_powder", "banner", "wall_banner");

    private BedScanner() {}

    private static final List<String> TEAM_ORDER = List.of("red", "blue", "green", "yellow", "aqua", "white", "pink", "gray");

    public static List<String> teams(int count) {
        return TEAM_ORDER.subList(0, Math.min(count, TEAM_ORDER.size()));
    }

    public static boolean isTeamOrder(String[] sides) {
        int start = TEAM_ORDER.indexOf(sides[0]);
        if (sides.length != 8 || start == -1) return false;
        boolean forward = true;
        boolean backward = true;
        for (int i = 0; i < 8; i++) {
            forward &= sides[i].equals(TEAM_ORDER.get((start + i) % 8));
            backward &= sides[i].equals(TEAM_ORDER.get((start - i + 8) % 8));
        }
        return forward || backward;
    }

    public static List<BlockPos> findBeds(ClientLevel level) {
        List<BlockPos> beds = new ArrayList<>();
        for (int cx = -CHUNK_RADIUS; cx <= CHUNK_RADIUS; cx++) {
            for (int cz = -CHUNK_RADIUS; cz <= CHUNK_RADIUS; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) continue;
                LevelChunkSection[] sections = chunk.getSections();
                for (int s = 0; s < sections.length; s++) {
                    LevelChunkSection section = sections[s];
                    if (section.hasOnlyAir() || !section.maybeHas(state -> state.getBlock() instanceof BedBlock)) continue;
                    int baseY = chunk.getSectionYFromSectionIndex(s) * 16;
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                if (section.getBlockState(x, y, z).getBlock() instanceof BedBlock) beds.add(new BlockPos(cx * 16 + x, baseY + y, cz * 16 + z));
                            }
                        }
                    }
                }
            }
        }
        return beds;
    }

    public static List<List<BlockPos>> group(List<BlockPos> blocks) {
        List<List<BlockPos>> groups = new ArrayList<>();
        outer:
        for (BlockPos pos : blocks) {
            for (List<BlockPos> g : groups) {
                for (BlockPos other : g) {
                    if (pos.distManhattan(other) <= 1) {
                        g.add(pos);
                        continue outer;
                    }
                }
            }
            groups.add(new ArrayList<>(List.of(pos)));
        }
        return groups;
    }

    public static List<Result> identify(ClientLevel level, int teamCount) {
        List<List<BlockPos>> beds = group(findBeds(level));
        List<String> teams = teams(teamCount);
        if (beds.isEmpty() || beds.size() > teams.size()) return List.of();
        List<Map<String, Double>> scores = new ArrayList<>();
        for (List<BlockPos> bed : beds) scores.add(nearbyColors(level, bed.getFirst()));
        removeShared(scores);
        List<Double> angles = new ArrayList<>();
        for (List<BlockPos> bed : beds) angles.add(Math.atan2(bed.getFirst().getZ(), bed.getFirst().getX()));
        String[] colors = pick(scores, teams, angles);
        List<Result> results = new ArrayList<>();
        for (int i = 0; i < beds.size(); i++) results.add(new Result(beds.get(i), colors[i], scores.get(i).getOrDefault(colors[i], 0.0)));
        return results;
    }

    private static Map<String, Double> nearbyColors(ClientLevel level, BlockPos center) {
        Map<String, Double> counts = new HashMap<>();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-NEARBY_XZ, -NEARBY_Y, -NEARBY_XZ), center.offset(NEARBY_XZ, NEARBY_Y, NEARBY_XZ))) {
            String color = blockColor(level.getBlockState(pos).getBlock());
            if (color != null) counts.merge(color, 1 / (1 + Math.sqrt(pos.distSqr(center))), Double::sum);
        }
        return counts;
    }

    static void removeShared(List<Map<String, Double>> scores) {
        if (scores.size() > 1) {
            Map<String, Double> shared = new HashMap<>(scores.getFirst());
            for (Map<String, Double> s : scores) shared.replaceAll((color, v) -> Math.min(v, s.getOrDefault(color, 0.0)));
            for (Map<String, Double> s : scores) s.replaceAll((color, v) -> v - shared.getOrDefault(color, 0.0));
        }
        for (Map<String, Double> s : scores) {
            double total = s.values().stream().mapToDouble(Double::doubleValue).sum();
            s.replaceAll((color, v) -> total > 0 ? v / total : 0);
        }
    }

    static String blockColor(Block block) {
        String path = BuiltInRegistries.BLOCK.getKey(block).getPath();
        for (DyeColor dye : DyeColor.values()) {
            String prefix = dye.getName() + "_";
            if (path.startsWith(prefix) && COLORED_SUFFIXES.contains(path.substring(prefix.length()))) return teamColor(dye);
        }
        return null;
    }

    static String teamColor(DyeColor color) {
        return switch (color) {
            case CYAN, LIGHT_BLUE -> "aqua";
            case LIME -> "green";
            case LIGHT_GRAY -> "gray";
            case MAGENTA -> "pink";
            default -> color.getName();
        };
    }

    static String[] pick(List<Map<String, Double>> scores, List<String> teams, List<Double> angles) {
        String[] free = assign(scores, teams);
        if (scores.size() != 8 || teams.size() != 8) return free;
        for (int i = 0; i < free.length; i++) {
            if (scores.get(i).getOrDefault(free[i], 0.0) < CLEAR_SCORE) return assignInOrder(scores, angles);
        }
        return free;
    }

    static String[] assign(List<Map<String, Double>> scores, List<String> teams) {
        String[] best = new String[scores.size()];
        String[] current = new String[scores.size()];
        double[] bestScore = {-1};
        assign(scores, teams, new boolean[teams.size()], current, 0, 0, best, bestScore);
        return best;
    }

    static String[] assignInOrder(List<Map<String, Double>> scores, List<Double> angles) {
        Integer[] byAngle = new Integer[scores.size()];
        for (int i = 0; i < byAngle.length; i++) byAngle[i] = i;
        Arrays.sort(byAngle, (a, b) -> Double.compare(angles.get(a), angles.get(b)));
        String[] best = null;
        double bestScore = -1;
        for (int start = 0; start < 8; start++) {
            for (int dir = -1; dir <= 1; dir += 2) {
                String[] colors = new String[byAngle.length];
                double sum = 0;
                for (int i = 0; i < byAngle.length; i++) {
                    String color = TEAM_ORDER.get(Math.floorMod(start + dir * i, 8));
                    colors[byAngle[i]] = color;
                    sum += scores.get(byAngle[i]).getOrDefault(color, 0.0);
                }
                if (sum > bestScore) {
                    bestScore = sum;
                    best = colors;
                }
            }
        }
        return best;
    }

    private static void assign(List<Map<String, Double>> scores, List<String> teams, boolean[] used, String[] current, int bed, double sum, String[] best, double[] bestScore) {
        if (bed == scores.size()) {
            if (sum > bestScore[0]) {
                bestScore[0] = sum;
                System.arraycopy(current, 0, best, 0, current.length);
            }
            return;
        }
        for (int t = 0; t < teams.size(); t++) {
            if (used[t]) continue;
            used[t] = true;
            current[bed] = teams.get(t);
            assign(scores, teams, used, current, bed + 1, sum + scores.get(bed).getOrDefault(teams.get(t), 0.0), best, bestScore);
            used[t] = false;
        }
    }

    static int side(int x, int z) {
        return (int) ((Math.atan2(z, x) + Math.PI * 4) / Math.toRadians(45)) % 8;
    }

    public static String[] sides(List<Bed> beds) {
        String[] result = new String[8];
        if (beds.isEmpty()) return result;
        List<Map<String, Integer>> counts = new ArrayList<>();
        for (int i = 0; i < 8; i++) counts.add(new HashMap<>());
        for (Bed bed : beds) counts.get(side(bed.x, bed.z)).merge(bed.color, 1, Integer::sum);

        for (int i = 0; i < 8; i++) {
            Map<String, Integer> c = counts.get(i);
            if (!c.isEmpty()) {
                result[i] = c.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
                continue;
            }
            double center = (i + 0.5) * Math.PI / 4;
            Bed nearest = null;
            double best = Double.MAX_VALUE;
            for (Bed bed : beds) {
                double d = Math.abs(Math.atan2(bed.z, bed.x) - center) % (Math.PI * 2);
                d = Math.min(d, Math.PI * 2 - d);
                if (d < best) {
                    best = d;
                    nearest = bed;
                }
            }
            result[i] = nearest.color;
        }
        return result;
    }

    public static void main(String[] args) {
        List<Bed> four = List.of(
                new Bed(50, 40, "blue"), new Bed(40, 50, "blue"),
                new Bed(-40, 50, "green"), new Bed(-50, 40, "green"),
                new Bed(-50, -40, "yellow"), new Bed(-40, -50, "yellow"),
                new Bed(40, -50, "red"), new Bed(50, -40, "red"));
        if (!String.join(",", sides(four)).equals("blue,blue,green,green,yellow,yellow,red,red")) throw new AssertionError();
        List<Bed> axes = List.of(new Bed(60, 1, "red"), new Bed(-1, 60, "blue"), new Bed(-60, -1, "green"), new Bed(1, -60, "yellow"));
        if (!String.join(",", sides(axes)).equals("red,blue,blue,green,green,yellow,yellow,red")) throw new AssertionError(String.join(",", sides(axes)));
        List<Bed> eight = new ArrayList<>();
        String[] colors = {"yellow", "aqua", "white", "pink", "gray", "red", "blue", "green"};
        for (int i = 0; i < 8; i++) {
            double a = (i + 0.5) * Math.PI / 4;
            eight.add(new Bed((int) Math.round(Math.cos(a) * 80), (int) Math.round(Math.sin(a) * 80), colors[i]));
        }
        if (!String.join(",", sides(eight)).equals(String.join(",", colors))) throw new AssertionError();
        List<Map<String, Double>> scores = List.of(
                Map.of("white", 0.6, "red", 0.4),
                Map.of("white", 0.5, "blue", 0.5),
                Map.of("white", 0.7, "green", 0.2),
                Map.of("yellow", 0.9));
        if (!String.join(",", assign(scores, teams(4))).equals("red,blue,green,yellow")) throw new AssertionError(String.join(",", assign(scores, teams(4))));
        if (!isTeamOrder("yellow,aqua,white,pink,gray,red,blue,green".split(","))) throw new AssertionError();
        if (!isTeamOrder("green,blue,red,gray,pink,white,aqua,yellow".split(","))) throw new AssertionError();
        if (isTeamOrder("pink,gray,yellow,green,red,red,aqua,aqua".split(","))) throw new AssertionError();
        List<Map<String, Double>> eightScores = new ArrayList<>();
        List<Double> angles = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            eightScores.add(Map.of(i == 5 ? "white" : colors[i], 0.8));
            angles.add((i + 0.5) * Math.PI / 4 - (i >= 4 ? Math.PI * 2 : 0));
        }
        if (!String.join(",", assignInOrder(eightScores, angles)).equals(String.join(",", colors))) throw new AssertionError(String.join(",", assignInOrder(eightScores, angles)));
        if (!String.join(",", pick(eightScores, teams(8), angles)).equals(String.join(",", colors))) throw new AssertionError(String.join(",", pick(eightScores, teams(8), angles)));
        String[] echo = {"red", "blue", "green", "yellow", "aqua", "white", "pink", "gray"};
        List<Map<String, Double>> echoScores = new ArrayList<>();
        List<Double> echoAngles = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            echoScores.add(Map.of(echo[i], 0.9, "brown", 0.1));
            echoAngles.add(Math.atan2(-72 + (i % 4) * 48, i < 4 ? -81 : 80));
        }
        if (!String.join(",", pick(echoScores, teams(8), echoAngles)).equals(String.join(",", echo))) throw new AssertionError(String.join(",", pick(echoScores, teams(8), echoAngles)));
        List<Map<String, Double>> desert = new ArrayList<>();
        for (String team : teams(4)) {
            Map<String, Double> counts = new HashMap<>(Map.of("red", 60.0, "white", 40.0));
            counts.merge(team, 10.0, Double::sum);
            desert.add(counts);
        }
        removeShared(desert);
        if (!String.join(",", assign(desert, teams(4))).equals("red,blue,green,yellow")) throw new AssertionError(String.join(",", assign(desert, teams(4))));
        if (desert.get(1).get("blue") != 1.0) throw new AssertionError(desert.get(1));
        System.out.println("ok");
    }
}
