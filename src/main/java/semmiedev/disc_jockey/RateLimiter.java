package semmiedev.disc_jockey;

import net.minecraft.util.Util;

public class RateLimiter {

    private static final long UNINITIALIZED = -1L; // Magic timestamp

    // Timestamps are from Util.getMeasuringTimeMs() (which is System.nanos() converted to ms)
    private static long now() {
        return Util.getMeasuringTimeMs();
    }

    // Used to check and enforce packet rate limits to not get kicked
    private long last100MsSpanAt = UNINITIALIZED;
    private int last100MsSpanEstimatedPackets = 0;
    // At how many packets/100ms should the player just reduce / stop sending packets for a while
    private long reducePacketsUntil = UNINITIALIZED, stopPacketsUntil = UNINITIALIZED;

    // Use to limit swings and look to only each tick. More will not be visually visible anyway due to interpolation
    private long lastLookSentAt = UNINITIALIZED, lastSwingSentAt = UNINITIALIZED;

    public int getMaxCosmeticPacketsPer100ms() {
        return Main.config.playbackPacketRatelimit.getReducePacketsPer100Millis();
    }

    public int getMaxPacketsPer100ms() {
        return Main.config.playbackPacketRatelimit.getMaxPacketsPer100Millis();
    }

    /**
     * Should run each tick, but does not need to.
     * Just run before running a lot of ŕate limit checks / on*Packet()
     */
    public void tick() {
        final long now = now();
        if(last100MsSpanAt != UNINITIALIZED && now - last100MsSpanAt >= 100) {
            last100MsSpanEstimatedPackets = 0;
            last100MsSpanAt = now;
        }else if (last100MsSpanAt == UNINITIALIZED) {
            last100MsSpanAt = now;
            last100MsSpanEstimatedPackets = 0;
        }
    }

    public void onPacketSent() {
        last100MsSpanEstimatedPackets++;
        checkLimits();
    }

    public void onLookPacketSent() {
        lastLookSentAt = now();
        onPacketSent();
    }

    public void onSwingPacketSent() {
        lastSwingSentAt = now();
        onPacketSent();
    }

    public void checkLimits() {
        if(last100MsSpanEstimatedPackets >= getMaxCosmeticPacketsPer100ms()) {
            reducePacketsUntil = Math.max(reducePacketsUntil, now() + 500);
        }
        if(last100MsSpanEstimatedPackets >= getMaxPacketsPer100ms()) {
            Main.LOGGER.info("Stopping all packets for a bit!");
            final long now = now();
            stopPacketsUntil = Math.max(stopPacketsUntil, now + 250);
            reducePacketsUntil = Math.max(reducePacketsUntil, now + 10000);
        }
    }

    public boolean canSendCosmeticPacket() {
        return last100MsSpanEstimatedPackets < getMaxCosmeticPacketsPer100ms() && (reducePacketsUntil == UNINITIALIZED || reducePacketsUntil < now());
    }

    public boolean canSendAnyPacket() {
        return last100MsSpanEstimatedPackets < getMaxPacketsPer100ms() && (stopPacketsUntil == UNINITIALIZED || stopPacketsUntil < now());
    }

    public boolean canSendLookPacket() {
        return (lastLookSentAt == UNINITIALIZED || now() - lastLookSentAt >= 50) && canSendCosmeticPacket();
    }

    public boolean canSendSwingPacket() {
        return (lastSwingSentAt == UNINITIALIZED || now() - lastSwingSentAt >= 50) && canSendCosmeticPacket();
    }

}
