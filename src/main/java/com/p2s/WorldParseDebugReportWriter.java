package com.p2s;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

public final class WorldParseDebugReportWriter {
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter HUMAN_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");

    private WorldParseDebugReportWriter() {
    }

    public static Path write(WorldStructureParser.ParseDebugReport report) throws IOException {
        Path dir = FabricLoader.getInstance().getGameDir().resolve("logs").resolve("p2s-world-parse-debug");
        Files.createDirectories(dir);
        String timestamp = FILE_TS.format(report.generatedAt().atZone(ZoneId.systemDefault()));
        String fileName = "world-parse-" + timestamp
                + "-" + sanitize(report.playerName())
                + "-" + sanitize(report.dimensionId())
                + ".log";
        Path output = dir.resolve(fileName);
        Files.writeString(output, format(report));
        return output;
    }

    public static void logSummary(WorldStructureParser.ParseDebugReport report, Path dumpPath) {
        P2SMod.LOGGER.info(
                "World parse debug complete -> player={}, dimension={}, min={}, max={}, size={}x{}x{}, volume={}, nonAir={}, air={}, specialUnits={}, dump={}",
                report.playerName(),
                report.dimensionId(),
                WorldStructureParser.formatPos(report.selectionMin()),
                WorldStructureParser.formatPos(report.selectionMax()),
                report.selectionSize().getX(),
                report.selectionSize().getY(),
                report.selectionSize().getZ(),
                report.selectionVolume(),
                report.nonAirBlocks(),
                report.airBlocks(),
                report.specialUnits().size(),
                dumpPath
        );
        for (WorldStructureParser.ParsedStructureUnit unit : report.specialUnits()) {
            P2SMod.LOGGER.info(
                    "World parse special unit [{}] rel={} world={} block={} members={} notes={}",
                    unit.kind(),
                    WorldStructureParser.formatPos(unit.anchorRelativePos()),
                    WorldStructureParser.formatPos(unit.anchorWorldPos()),
                    unit.blockId(),
                    unit.members().size(),
                    unit.notes().isEmpty() ? "-" : String.join(" | ", unit.notes())
            );
        }
    }

    public static String format(WorldStructureParser.ParseDebugReport report) {
        StringBuilder sb = new StringBuilder(16 * 1024);
        sb.append("P2S World Parse Debug Report\n");
        sb.append("Generated: ").append(HUMAN_TS.format(report.generatedAt().atZone(ZoneId.systemDefault()))).append('\n');
        sb.append("Player: ").append(blank(report.playerName())).append('\n');
        sb.append("Dimension: ").append(blank(report.dimensionId())).append('\n');
        sb.append("Selection Min: ").append(WorldStructureParser.formatPos(report.selectionMin())).append('\n');
        sb.append("Selection Max: ").append(WorldStructureParser.formatPos(report.selectionMax())).append('\n');
        sb.append("Selection Size: ")
                .append(report.selectionSize().getX()).append('x')
                .append(report.selectionSize().getY()).append('x')
                .append(report.selectionSize().getZ()).append('\n');
        sb.append("Selection Volume: ").append(report.selectionVolume()).append('\n');
        sb.append("Non-Air Blocks: ").append(report.nonAirBlocks()).append('\n');
        sb.append("Air Blocks: ").append(report.airBlocks()).append('\n');
        sb.append("Special Units: ").append(report.specialUnits().size()).append('\n');
        sb.append('\n');

        appendCounts(sb, "Block Counts", report.blockCounts());
        appendCounts(sb, "Special Unit Counts", report.unitCounts());

        sb.append("== Special Units ==\n");
        if (report.specialUnits().isEmpty()) {
            sb.append("(none)\n\n");
        } else {
            int index = 1;
            for (WorldStructureParser.ParsedStructureUnit unit : report.specialUnits()) {
                sb.append('#').append(index++).append('\n');
                sb.append("kind: ").append(unit.kind()).append('\n');
                sb.append("anchor_world: ").append(WorldStructureParser.formatPos(unit.anchorWorldPos())).append('\n');
                sb.append("anchor_relative: ").append(WorldStructureParser.formatPos(unit.anchorRelativePos())).append('\n');
                sb.append("block: ").append(unit.blockId()).append('\n');
                if (!unit.anchorProperties().isEmpty()) {
                    sb.append("anchor_properties: ").append(formatMap(unit.anchorProperties())).append('\n');
                }
                if (!unit.notes().isEmpty()) {
                    sb.append("notes:\n");
                    for (String note : unit.notes()) {
                        sb.append("  - ").append(note).append('\n');
                    }
                }
                sb.append("members:\n");
                for (WorldStructureParser.ParsedBlockRecord member : unit.members()) {
                    appendRecordLine(sb, member, "  - ");
                }
                sb.append('\n');
            }
        }

        sb.append("== Raw Non-Air Blocks ==\n");
        if (report.rawBlocks().isEmpty()) {
            sb.append("(none)\n");
            return sb.toString();
        }
        for (WorldStructureParser.ParsedBlockRecord record : report.rawBlocks()) {
            appendRecordLine(sb, record, "- ");
        }
        return sb.toString();
    }

    private static void appendCounts(StringBuilder sb, String title, Map<String, Integer> counts) {
        sb.append("== ").append(title).append(" ==\n");
        if (counts == null || counts.isEmpty()) {
            sb.append("(none)\n\n");
            return;
        }
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            sb.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
        }
        sb.append('\n');
    }

    private static void appendRecordLine(StringBuilder sb, WorldStructureParser.ParsedBlockRecord record, String prefix) {
        sb.append(prefix)
                .append("rel=").append(WorldStructureParser.formatPos(record.relativePos()))
                .append(" world=").append(WorldStructureParser.formatPos(record.worldPos()))
                .append(" block=").append(record.blockId())
                .append(" state=").append(record.stateString());
        if (record.tags() != null && !record.tags().isEmpty()) {
            sb.append(" tags=").append(record.tags());
        }
        if (record.blockEntityType() != null && !record.blockEntityType().isBlank()) {
            sb.append(" block_entity=").append(record.blockEntityType());
        }
        sb.append('\n');
        if (record.blockEntityNbt() != null && !record.blockEntityNbt().isBlank()) {
            sb.append("    nbt: ").append(record.blockEntityNbt()).append('\n');
        }
    }

    private static String formatMap(Map<String, String> properties) {
        if (properties == null || properties.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }
        sb.append('}');
        return sb.toString();
    }

    private static String sanitize(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return "unknown";
        }
        return normalized.replaceAll("[^a-z0-9._-]+", "_");
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? "(blank)" : value;
    }
}
