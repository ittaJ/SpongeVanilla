/*
 * This file is part of Sponge, licensed under the MIT License (MIT).
 *
 * Copyright (c) SpongePowered <https://www.spongepowered.org>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.spongepowered.server.mixin.core.server;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.crash.CrashReport;
import net.minecraft.network.NetworkSystem;
import net.minecraft.network.ServerStatusResponse;
import net.minecraft.network.play.server.SPacketTimeUpdate;
import net.minecraft.profiler.Profiler;
import net.minecraft.profiler.Snooper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.PlayerList;
import net.minecraft.util.ITickable;
import net.minecraft.util.ReportedException;
import net.minecraft.util.Util;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.world.WorldServer;
import org.apache.logging.log4j.Logger;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.event.SpongeEventFactory;
import org.spongepowered.api.world.World;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.common.SpongeImpl;
import org.spongepowered.common.interfaces.IMixinMinecraftServer;
import org.spongepowered.common.interfaces.world.IMixinWorldServer;
import org.spongepowered.common.text.SpongeTexts;
import org.spongepowered.common.world.WorldManager;
import org.spongepowered.server.SpongeVanilla;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

// SpongeCommon injects into updateTimeLightAndEntities, so we need to apply
// our @Overwrite *before* SpongeCommon's mixin is applied, otherwise it will fail
@Mixin(value = MinecraftServer.class, priority = 999)
public abstract class MixinMinecraftServer implements IMixinMinecraftServer {

    @com.google.inject.Inject private static SpongeVanilla spongeVanilla;
    @Shadow @Final private static Logger LOGGER;
    @Shadow @Final private Snooper usageSnooper;

    @Shadow @Final private List<ITickable> tickables;
    @Shadow @Final public Profiler profiler;
    @Shadow private PlayerList playerList;
    @Shadow private int tickCounter;
    @Shadow @Final protected Queue<FutureTask<?>> futureTaskQueue;

    @Shadow public abstract boolean getAllowNether();
    @Shadow public abstract NetworkSystem getNetworkSystem();

    private boolean skipServerStop = false;

    private final Int2ObjectMap<long[]> worldTickTimes = new Int2ObjectOpenHashMap<>(3);

    /**
     * @author Minecrell
     * @reason Sets the server brand name to 'sponge'
     */
    @Overwrite
    public String getServerModName() {
        return spongeVanilla.getName();
    }

    /**
     * @author Minecrell
     * @reason Logs chat messages with legacy color codes to show colored
     *     messages in the console
     */
    @Overwrite
    public void sendMessage(ITextComponent component) {
        LOGGER.info(SpongeTexts.toLegacy(component));
    }

    @Inject(method = "applyServerIconToResponse", at = @At("HEAD"), cancellable = true)
    private void onAddFaviconToStatusResponse(ServerStatusResponse response, CallbackInfo ci) {
        // Don't load favicon twice
        if (response.getFavicon() != null) {
            ci.cancel();
        }
    }

    /**
     * @author Zidane - Chris Sanders
     * @reason need to save player stuff for sponge
     */
    @Overwrite
    public void stopServer() {

        // stopServer is called from both the shutdown hook AND the finally statement in the main game loop, no reason to do this twice..
        if (skipServerStop) {
            return;
        }

        skipServerStop = true;

        LOGGER.info("Stopping server");

        spongeVanilla.onServerStopping();

        final MinecraftServer server = (MinecraftServer) (Object) this;

        // Sponge Start - Force player profile cache save
        server.getPlayerProfileCache().save();

        if (this.getNetworkSystem() != null) {
            this.getNetworkSystem().terminateEndpoints();
        }

        if (this.playerList != null) {
            LOGGER.info("Saving players");
            this.playerList.saveAllPlayerData();
            this.playerList.removeAllPlayers();
        }

        if (server.worlds != null) {
            LOGGER.info("Saving worlds");

            for (WorldServer worldserver : server.worlds) {
                if (worldserver != null) {
                    worldserver.disableLevelSaving = false;
                }
            }

            server.saveAllWorlds(false);

            for (WorldServer worldserver1 : server.worlds) {
                if (worldserver1 != null) {
                    // Turn off Async Lighting
                    if (SpongeImpl.getGlobalConfig().getConfig().getModules().useOptimizations() &&
                        SpongeImpl.getGlobalConfig().getConfig().getOptimizations().useAsyncLighting()) {
                        ((IMixinWorldServer) worldserver1).getLightingExecutor().shutdown();

                        try {
                            ((IMixinWorldServer) worldserver1).getLightingExecutor().awaitTermination(1, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        } finally {
                            ((IMixinWorldServer) worldserver1).getLightingExecutor().shutdownNow();
                        }
                    }

                    WorldManager.unloadWorld(worldserver1, false);
                }
            }

            if (this.usageSnooper.isSnooperRunning()) {
                this.usageSnooper.stopSnooper();
            }
        }
    }

    @Override
    public long[] getWorldTickTimes(int dimensionId) {
        return this.worldTickTimes.get(dimensionId);
    }

    @Override
    public void putWorldTickTimes(int dimensionId, long[] tickTimes) {
        this.worldTickTimes.put(dimensionId, tickTimes);
    }

    @Override
    public void removeWorldTickTimes(int dimensionId) {
        this.worldTickTimes.remove(dimensionId);
    }

    /**
     * @author Zidane
     * @reason Handles ticking the additional worlds loaded by Sponge.
     */
    @Overwrite
    public void updateTimeLightAndEntities() {
        this.profiler.startSection("jobs");

        synchronized (this.futureTaskQueue) {
            while (!this.futureTaskQueue.isEmpty()) {
                Util.runTask(this.futureTaskQueue.poll(), LOGGER);
            }
        }

        this.profiler.endStartSection("levels");
        tickChunkLoader(); // Sponge: Tick chunk loader

        // Sponge start - Iterate over all our dimensions
        for (final ObjectIterator<Int2ObjectMap.Entry<WorldServer>> it = WorldManager.worldsIterator(); it.hasNext();) {
            Int2ObjectMap.Entry<WorldServer> entry = it.next();
            final WorldServer worldServer = entry.getValue();
            // Sponge end
            long i = System.nanoTime();

            if (entry.getIntKey() == 0 || this.getAllowNether()) {

                // Sponge start - copy from SpongeCommon MixinMinecraftServer
                IMixinWorldServer spongeWorld = (IMixinWorldServer) worldServer;
                if (spongeWorld.getChunkGCTickInterval() > 0) {
                    spongeWorld.doChunkGC();
                }
                // Sponge end

                this.profiler.startSection(worldServer.getWorldInfo().getWorldName());

                if (this.tickCounter % 20 == 0) {
                    this.profiler.startSection("timeSync");
                    this.playerList.sendPacketToAllPlayersInDimension (
                            new SPacketTimeUpdate(worldServer.getTotalWorldTime(), worldServer.getWorldTime(),
                                    worldServer.getGameRules().getBoolean("doDaylightCycle")), ((IMixinWorldServer) worldServer).getDimensionId());
                    this.profiler.endSection();
                }

                this.profiler.startSection("tick");

                try {
                    worldServer.tick();
                } catch (Throwable throwable1) {
                    CrashReport crashreport = CrashReport.makeCrashReport(throwable1, "Exception ticking world");
                    worldServer.addWorldInfoToCrashReport(crashreport);
                    throw new ReportedException(crashreport);
                }

                try {
                    worldServer.updateEntities();
                } catch (Throwable throwable) {
                    CrashReport crashreport1 = CrashReport.makeCrashReport(throwable, "Exception ticking world entities");
                    worldServer.addWorldInfoToCrashReport(crashreport1);
                    throw new ReportedException(crashreport1);
                }

                this.profiler.endSection();
                this.profiler.startSection("tracker");

                // Sponge start - copy from SpongeCommon MixinMinecraftServer
                if (spongeWorld.getChunkGCTickInterval() > 0) {
                    worldServer.getChunkProvider().tick();
                }
                // Sponge end

                worldServer.getEntityTracker().tick();
                this.profiler.endSection();
                this.profiler.endSection();
            }

            // Sponge start - Write tick times to our custom map
            this.worldTickTimes.get(entry.getIntKey())[this.tickCounter % 100] = System.nanoTime() - i;
            // Sponge end
        }

        // Sponge start - Unload requested worlds
        this.profiler.endStartSection("dim_unloading");
        WorldManager.unloadQueuedWorlds();
        // Sponge end

        this.profiler.endStartSection("connection");
        this.getNetworkSystem().networkTick();
        this.profiler.endStartSection("players");
        this.playerList.onTick();
        this.profiler.endStartSection("tickables");

        for (int k = 0; k < this.tickables.size(); ++k) {
            this.tickables.get(k).update();
        }

        this.profiler.endSection();
    }

    // This is used by asynchronous chunk loading to finish loading the chunks
    private void tickChunkLoader() {
    }

}
