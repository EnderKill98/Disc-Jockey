package semmiedev.disc_jockey;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.Instrument;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Pair;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.*;
import net.minecraft.world.GameMode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class SongPlayer implements ClientTickEvents.StartWorldTick {
    private static boolean warned;

    public boolean running;
    public Song song;

    private int index;
    private float tick;
    private HashMap<Instrument, HashMap<Byte, BlockPos>> noteBlocks = null;
    private boolean tuned;
    private long lastPlaybackTickAt = -1L;

    // Used to check and enforce packet rate limits to not get kicked
    private long last100MsSpanAt = -1L;
    private int last100MsSpanEstimatedPackets = 0;
    // At how many packets/100ms should the player just reduce / stop sending packets for a while
    final private int last100MsReducePacketsAfter = 300 / 10, last100MsStopPacketsAfter = 450 / 10;
    // If higher than current millis, don't send any packets of this kind (temp disable)
    private long reducePacketsUntil = -1L, stopPacketsUntil = -1L;

    // Use to limit swings and look to only each tick. More will not be visually visible anyway due to interpolation
    private long lastLookSentAt = -1L, lastSwingSentAt = -1L;

    // The thread executing the tickPlayback method
    private Thread playbackThread = null;
    public long playbackLoopDelay = 5;
    // Just for external debugging purposes
    public HashMap<Block, Integer> missingInstrumentBlocks = new HashMap<>();
    public float speed = 1.0f;

    private long lastInteractAt = -1;
    private float availableInteracts = 8;
    private int tuneInitialUntunedBlocks = -1;
    private HashMap<BlockPos, Pair<Integer, Long>> notePredictions = new HashMap<>();

    public synchronized void startPlaybackThread() {
        this.playbackThread = new Thread(() -> {
            Thread ownThread = this.playbackThread;
            while(ownThread == this.playbackThread) {
                try {
                    // Accuracy doesn't really matter at this precision imo
                    Thread.sleep(playbackLoopDelay);
                }catch (Exception ex) {
                    ex.printStackTrace();
                }
                tickPlayback();
            }
        });
        this.playbackThread.start();
    }

    public synchronized void stopPlaybackThread() {
        this.playbackThread = null; // Should stop on its own then
    }

    public synchronized void start(Song song) {
        if (!Main.config.hideWarning && !warned) {
            MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(Text.translatable("disc_jockey.warning").formatted(Formatting.BOLD, Formatting.RED));
            warned = true;
            return;
        }
        if (running) stop();
        this.song = song;
        Main.TICK_LISTENERS.add(this);
        if(this.playbackThread == null) startPlaybackThread();
        running = true;
        lastPlaybackTickAt = System.currentTimeMillis();
        last100MsSpanAt = System.currentTimeMillis();
        last100MsSpanEstimatedPackets = 0;
        reducePacketsUntil = -1L;
        stopPacketsUntil = -1L;
        lastLookSentAt = -1L;
        lastSwingSentAt = -1L;
        missingInstrumentBlocks.clear();
    }

    public synchronized void stop() {
        MinecraftClient.getInstance().send(() -> Main.TICK_LISTENERS.remove(this));
        stopPlaybackThread();
        running = false;
        index = 0;
        tick = 0;
        noteBlocks = null;
        notePredictions.clear();
        tuned = false;
        tuneInitialUntunedBlocks = -1;
        lastPlaybackTickAt = -1L;
        last100MsSpanAt = -1L;
        last100MsSpanEstimatedPackets = 0;
        reducePacketsUntil = -1L;
        stopPacketsUntil = -1L;
        lastLookSentAt = -1L;
        lastSwingSentAt = -1L;
    }

    public synchronized void tickPlayback() {
        if (!running) {
            lastPlaybackTickAt = -1L;
            last100MsSpanAt = -1L;
            return;
        }
        long previousPlaybackTickAt = lastPlaybackTickAt;
        lastPlaybackTickAt = System.currentTimeMillis();
        if(last100MsSpanAt != -1L && System.currentTimeMillis() - last100MsSpanAt >= 100) {
            last100MsSpanEstimatedPackets = 0;
            last100MsSpanAt = System.currentTimeMillis();
        }else if (last100MsSpanAt == -1L) {
            last100MsSpanAt = System.currentTimeMillis();
            last100MsSpanEstimatedPackets = 0;
        }
        if(noteBlocks != null && tuned) {
            while (running) {
                MinecraftClient client = MinecraftClient.getInstance();
                GameMode gameMode = client.interactionManager.getCurrentGameMode();
                if (!gameMode.isSurvivalLike()) {
                    client.inGameHud.getChatHud().addMessage(Text.translatable(Main.MOD_ID+".player.invalid_game_mode", gameMode.getTranslatableName()).formatted(Formatting.RED));
                    stop();
                    return;
                }

                long note = song.notes[index];
                final long now = System.currentTimeMillis();
                if ((short)note <= Math.round(tick)) {
                    BlockPos blockPos = noteBlocks.get(Note.INSTRUMENTS[(byte)(note >> Note.INSTRUMENT_SHIFT)]).get((byte)(note >> Note.NOTE_SHIFT));
                    if (!canInteractWith(client.player, blockPos)) {
                        stop();
                        client.inGameHud.getChatHud().addMessage(Text.translatable(Main.MOD_ID+".player.to_far").formatted(Formatting.RED));
                        return;
                    }
                    Vec3d unit = Vec3d.ofCenter(blockPos, 0.5).subtract(client.player.getEyePos()).normalize();
                    if((lastLookSentAt == -1L || now - lastLookSentAt >= 50) && last100MsSpanEstimatedPackets < last100MsReducePacketsAfter && (reducePacketsUntil == -1L || reducePacketsUntil < now)) {
                        client.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(MathHelper.wrapDegrees((float) (MathHelper.atan2(unit.z, unit.x) * 57.2957763671875) - 90.0f), MathHelper.wrapDegrees((float) (-(MathHelper.atan2(unit.y, Math.sqrt(unit.x * unit.x + unit.z * unit.z)) * 57.2957763671875))), true));
                        last100MsSpanEstimatedPackets++;
                        lastLookSentAt = now;
                    }else if(last100MsSpanEstimatedPackets >= last100MsReducePacketsAfter){
                        reducePacketsUntil = Math.max(reducePacketsUntil, now + 500);
                    }
                    if(last100MsSpanEstimatedPackets < last100MsStopPacketsAfter && (stopPacketsUntil == -1L || stopPacketsUntil < now)) {
                        // TODO: 5/30/2022 Check if the block needs tuning
                        //client.interactionManager.attackBlock(blockPos, Direction.UP);
                        client.player.networkHandler.sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, blockPos, Direction.UP, 0));
                        last100MsSpanEstimatedPackets++;
                    }else if(last100MsSpanEstimatedPackets >= last100MsStopPacketsAfter) {
                        Main.LOGGER.info("Stopping all packets for a bit!");
                        stopPacketsUntil = Math.max(stopPacketsUntil, now + 250);
                        reducePacketsUntil = Math.max(reducePacketsUntil, now + 10000);
                    }
                    if(last100MsSpanEstimatedPackets < last100MsReducePacketsAfter && (reducePacketsUntil == -1L || reducePacketsUntil < now)) {
                        client.player.networkHandler.sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK, blockPos, Direction.UP, 0));
                        last100MsSpanEstimatedPackets++;
                    }else if(last100MsSpanEstimatedPackets >= last100MsReducePacketsAfter){
                        reducePacketsUntil = Math.max(reducePacketsUntil, now + 500);
                    }
                    if((lastSwingSentAt == -1L || now - lastSwingSentAt >= 50) &&last100MsSpanEstimatedPackets < last100MsReducePacketsAfter && (reducePacketsUntil == -1L || reducePacketsUntil < now)) {
                        client.executeSync(() -> client.player.swingHand(Hand.MAIN_HAND));
                        lastSwingSentAt = now;
                        last100MsSpanEstimatedPackets++;
                    }else if(last100MsSpanEstimatedPackets  >= last100MsReducePacketsAfter){
                        reducePacketsUntil = Math.max(reducePacketsUntil, now + 500);
                    }

                    index++;
                    if (index >= song.notes.length) {
                        stop();
                        break;
                    }
                } else {
                    break;
                }
            }

            // This math has some unnecessary stuff (using bogus tps). Can be simplified a lot.
            float tps = 1000.0F / 16.0F;
            if (previousPlaybackTickAt != -1L && lastPlaybackTickAt != -1L) {
                float inaccuracyFactor = (1000.0F / tps) / ((float) (lastPlaybackTickAt - previousPlaybackTickAt));
                tps *= inaccuracyFactor;
            }
            tps /= speed;

            tick += song.tempo / 100f / tps;
        }
    }

    // TODO: 6/2/2022 Play note blocks every song tick, instead of every tick. That way the song will sound better
    //      11/1/2023 Playback now done in separate thread. Not ideal but better especiall when FPS are low.
    @Override
    public void onStartTick(ClientWorld world) {
        MinecraftClient client = MinecraftClient.getInstance();
        if(world == null || client.world == null || client.player == null) return;

        // Clear outdated note predictions
        ArrayList<BlockPos> outdatedPredictions = new ArrayList<>();
        for(Map.Entry<BlockPos, Pair<Integer, Long>> entry : notePredictions.entrySet()) {
            if(entry.getValue().getRight() < System.currentTimeMillis())
                outdatedPredictions.add(entry.getKey());
        }
        for(BlockPos outdatedPrediction : outdatedPredictions) notePredictions.remove(outdatedPrediction);

        if (noteBlocks == null) {
            noteBlocks = new HashMap<>();

            ClientPlayerEntity player = client.player;

            ArrayList<Note> capturedNotes = new ArrayList<>();

            Vec3d playerPos = player.getEyePos();
            for (int x = -7; x <= 7; x++) {
                for (int y = -7; y <= 7; y++) {
                    for (int z = -7; z <= 7; z++) {
                        BlockPos blockPos = new BlockPos(playerPos.add(x, y, z));
                        if (canInteractWith(player, blockPos)) {
                            BlockState blockState = world.getBlockState(blockPos);
                            if (blockState.isOf(Blocks.NOTE_BLOCK) && world.isAir(blockPos.up())) {
                                for (Note note : song.uniqueNotes) {
                                    if (!capturedNotes.contains(note) && blockState.get(Properties.INSTRUMENT) == note.instrument) {
                                        getNotes(note.instrument).put(note.note, blockPos);
                                        capturedNotes.add(note);
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
            }

            ArrayList<Note> missingNotes = new ArrayList<>(song.uniqueNotes);
            missingNotes.removeAll(capturedNotes);
            if (!missingNotes.isEmpty()) {
                ChatHud chatHud = MinecraftClient.getInstance().inGameHud.getChatHud();
                chatHud.addMessage(Text.translatable(Main.MOD_ID+".player.invalid_note_blocks").formatted(Formatting.RED));

                HashMap<Block, Integer> missing = new HashMap<>();
                for (Note note : missingNotes) {
                    Block block = Note.INSTRUMENT_BLOCKS.get(note.instrument);
                    Integer got = missing.get(block);
                    if (got == null) got = 0;
                    missing.put(block, got + 1);
                }

                missingInstrumentBlocks = missing;
                missing.forEach((block, integer) -> chatHud.addMessage(Text.literal(block.getName().getString()+" × "+integer).formatted(Formatting.RED)));
                stop();
            }
        } else if (!tuned) {
            //tuned = true;

            int ping = 0;
            {
                PlayerListEntry playerListEntry;
                if (client.getNetworkHandler() != null && (playerListEntry = client.getNetworkHandler().getPlayerListEntry(client.player.getGameProfile().getId())) != null)
                    ping = playerListEntry.getLatency();
            }

            if(lastInteractAt != -1L) {
                // Paper allows 8 interacts per 300 ms
                availableInteracts += ((System.currentTimeMillis() - lastInteractAt) / (310.0 / 8.0));
                availableInteracts = Math.min(8f, Math.max(0f, availableInteracts));
            }else {
                availableInteracts = 8f;
                lastInteractAt = System.currentTimeMillis();
            }

            int fullyTunedBlocks = 0;
            HashMap<BlockPos, Integer> untunedNotes = new HashMap<>();
            for (Note note : song.uniqueNotes) {
                BlockPos blockPos = noteBlocks.get(note.instrument).get(note.note);
                if(blockPos == null) continue;
                BlockState blockState = world.getBlockState(blockPos);
                int assumedNote = notePredictions.containsKey(blockPos) ? notePredictions.get(blockPos).getLeft() : blockState.get(Properties.NOTE);

                if (blockState.contains(Properties.NOTE)) {
                    if(assumedNote == note.note && blockState.get(Properties.NOTE) == note.note)
                        fullyTunedBlocks++;
                    if (assumedNote != note.note) {
                        if (!canInteractWith(client.player, blockPos)) {
                            stop();
                            client.inGameHud.getChatHud().addMessage(Text.translatable(Main.MOD_ID+".player.to_far").formatted(Formatting.RED));
                            return;
                        }
                        untunedNotes.put(blockPos, blockState.get(Properties.NOTE));
                    }
                } else {
                    noteBlocks = null;
                    break;
                }
            }

            if(tuneInitialUntunedBlocks == -1 || tuneInitialUntunedBlocks < untunedNotes.size())
                tuneInitialUntunedBlocks = untunedNotes.size();

            if(untunedNotes.isEmpty() && fullyTunedBlocks == song.uniqueNotes.size()) {
                // Wait roundrip + 100ms before considering tuned after changing notes (in case the server rejects an interact)
                if(lastInteractAt == -1 || System.currentTimeMillis() - lastInteractAt >= ping * 2 + 100) {
                    tuned = true;
                    tuneInitialUntunedBlocks = -1;
                }
            }

            BlockPos lastBlockPos = null;
            int lastTunedNote = Integer.MIN_VALUE;
            float roughTuneProgress = 1 - (untunedNotes.size() / Math.max(tuneInitialUntunedBlocks + 0f, 1f));
            while(availableInteracts >= 1f && untunedNotes.size() > 0) {
                BlockPos blockPos = null;
                int searches = 0;
                while(blockPos == null) {
                    searches++;
                    // Find higher note
                    for (Map.Entry<BlockPos, Integer> entry : untunedNotes.entrySet()) {
                        if (entry.getValue() > lastTunedNote) {
                            blockPos = entry.getKey();
                            break;
                        }
                    }
                    // Find higher note or equal
                    if (blockPos == null) {
                        for (Map.Entry<BlockPos, Integer> entry : untunedNotes.entrySet()) {
                            if (entry.getValue() >= lastTunedNote) {
                                blockPos = entry.getKey();
                                break;
                            }
                        }
                    }
                    // Not found. Reset last note
                    if(blockPos == null)
                        lastTunedNote = Integer.MIN_VALUE;
                    if(blockPos == null && searches > 1) {
                        // Something went wrong. Take any note (one should at least exist here)
                        blockPos = untunedNotes.keySet().toArray(new BlockPos[0])[0];
                        break;
                    }
                }
                if(blockPos == null) return; // Something went very, very wrong!

                lastTunedNote = untunedNotes.get(blockPos);
                untunedNotes.remove(blockPos);
                int assumedNote = notePredictions.containsKey(blockPos) ? notePredictions.get(blockPos).getLeft() : client.world.getBlockState(blockPos).get(Properties.NOTE);
                notePredictions.put(blockPos, new Pair((assumedNote + 1) % 25, System.currentTimeMillis() + ping * 2 + 100));
                client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, new BlockHitResult(Vec3d.of(blockPos), Direction.UP, blockPos, false));
                lastInteractAt = System.currentTimeMillis();
                availableInteracts -= 1f;
                lastBlockPos = blockPos;
            }
            if(lastBlockPos != null) {
                // Turn head into spinning with time and lookup up further the further tuning is progressed
                client.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(((float) (System.currentTimeMillis() % 2000)) * (360f/2000f), (1 - roughTuneProgress) * 180 - 90, true));
                client.player.swingHand(Hand.MAIN_HAND);
            }
        }
    }

    private HashMap<Byte, BlockPos> getNotes(Instrument instrument) {
        return noteBlocks.computeIfAbsent(instrument, k -> new HashMap<>());
    }

    // The server limits interacts to 6 Blocks from Player Eye to Block Center
    private boolean canInteractWith(ClientPlayerEntity player, BlockPos blockPos) {
        return player.getEyePos().squaredDistanceTo(new Vec3d(blockPos.getX() + 0.5, blockPos.getY() + 0.5, blockPos.getZ() + 0.5)) <= 6.0*6.0;
    }
}
