package com.bergerkiller.bukkit.maplands;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;

import com.bergerkiller.bukkit.common.Task;
import com.bergerkiller.bukkit.common.bases.IntVector3;
import com.bergerkiller.bukkit.common.events.map.MapClickEvent;
import com.bergerkiller.bukkit.common.events.map.MapKeyEvent;
import com.bergerkiller.bukkit.common.map.MapBlendMode;
import com.bergerkiller.bukkit.common.map.MapCanvas;
import com.bergerkiller.bukkit.common.map.MapColorPalette;
import com.bergerkiller.bukkit.common.map.MapDisplay;
import com.bergerkiller.bukkit.common.map.MapSessionMode;
import com.bergerkiller.bukkit.common.map.MapTexture;
import com.bergerkiller.bukkit.common.map.MapPlayerInput.Key;
import com.bergerkiller.bukkit.common.utils.FaceUtil;
import com.bergerkiller.bukkit.common.utils.MathUtil;
import com.bergerkiller.bukkit.common.utils.WorldUtil;

/**
 * Main display class of Maplands. Renders the world on the map and provides
 * a UI menu. Makes use of the Canvas depth buffer capabilities to do the sprite rendering.
 */
public class MaplandsDisplay extends MapDisplay {
    private IsometricBlockSprites sprites;
    private MapTexture testSprite;
    private ZoomLevel zoom;
    private BlockFace facing;
    private Block startBlock;
    private final MapBlockBounds blockBounds = new MapBlockBounds();
    private int menuShowTicks = 0;
    private int menuSelectIndex = 0;
    private int currentRenderZ;
    private int minimumRenderZ;
    private int maximumRenderZ;
    private boolean forwardRenderNeeded;
    private int minCols, maxCols, minRows, maxRows;
    private boolean enableLight = false;
    private boolean[] drawnTiles; //TODO: Bitset may be faster/more memory efficient?
    private final HashSet<IntVector3> dirtyTiles = new HashSet<IntVector3>();
    private MenuButton[] menuButtons;
    private MapTexture menu_bg;
    int rendertime = 0;
    private static final int MENU_DURATION = 200; // amount of ticks menu is kept open while idle

    @Override
    public void onAttached() {
        this.getLayer().fill(Maplands.getBackgroundColor());
        this.menuButtons = new MenuButton[] {
                new MenuButton("zoom_in", 0, 0) {
                    public void onPressed() {
                        zoom(1);
                    }
                },
                new MenuButton("zoom_out", 32, 0) {
                    public void onPressed() {
                        zoom(-1);
                    }
                },
                new MenuButton("rotate_left", 64, 0) {
                    public void onPressed() {
                        rotate(1);
                    }
                },
                new MenuButton("rotate_right", 96, 0) {
                    public void onPressed() {
                        rotate(-1);
                    }
                }
        };
        this.menu_bg = MapTexture.loadResource(MaplandsDisplay.class, "/com/bergerkiller/bukkit/maplands/textures/menu_bg.png");
        for (MenuButton button : this.menuButtons) {
            button.setDisplay(this);
        }

        // Overwrite old sprites
        getLayer().setBlendMode(MapBlendMode.NONE);

        this.setSessionMode(MapSessionMode.FOREVER); // VIEWING for debug, FOREVER for release
        this.setReceiveInputWhenHolding(true);

        // Load from cache if possible
        if (Maplands.plugin.getCache().load(this.getMapInfo().uuid, this.getLayer())) {
            this.render(RenderMode.FROM_CACHE);
        } else {
            this.render(RenderMode.INITIALIZE);
        }

        refreshMapDisplayLookup();
    }

    @Override
    public void onDetached() {
        refreshMapDisplayLookup();

        // Save our current state to disk
        Maplands.plugin.getCache().save(this.getMapInfo().uuid, this.getLayer());
    }

    private static enum RenderMode {
        /** Renders the entire map from scratch */
        INITIALIZE,
        /** Renders assuming the map already contains color and depth info */
        FROM_CACHE,
        /** Renders assuming the map was translated, but has missing areas */
        TRANSLATION
    }

    private void render(RenderMode renderMode) {
        int px = properties.get("px", 0);
        int py = properties.get("py", 0);
        int pz = properties.get("pz", 0);
        this.facing = properties.get("facing", BlockFace.NORTH_EAST);
        this.zoom = properties.get("zoom", ZoomLevel.DEFAULT);
        String worldName = properties.get("mapWorld", "");
        if (worldName.length() == 0) {
            Player player = this.getOwners().get(0);
            worldName = player.getWorld().getName();
            px = player.getLocation().getBlockX();
            py = player.getLocation().getBlockY();
            pz = player.getLocation().getBlockZ();
            properties.set("px", px);
            properties.set("py", py);
            properties.set("pz", pz);
            properties.set("mapWorld", worldName);
            this.setMapItem(this.getMapItem());
        }
        World world = Bukkit.getWorld(worldName);

        // Get the correct sprites
        this.sprites = IsometricBlockSprites.getSprites(facing, this.zoom);
        this.testSprite = MapTexture.createEmpty(zoom.getWidth(), zoom.getHeight());

        // Start coordinates for the view
        this.startBlock = world.getBlockAt(px, py, pz);
        this.getLayer().setRelativeBrushMask(null);
        //this.getLayer().setDrawDepth(-VIEW_RANGE);
        //this.getLayer().fill(MapColorPalette.COLOR_RED);
        if (renderMode == RenderMode.INITIALIZE) {
            this.getLayer().clearDepthBuffer();
        }
        this.getLayer().setRelativeBrushMask(this.sprites.getBrushTexture());

        int nrColumns = this.sprites.getZoom().getNumberOfColumns(this.getWidth());
        int nrRows = this.sprites.getZoom().getNumberOfRows(this.getHeight());
        this.minCols = -nrColumns;
        this.maxCols = nrColumns;
        this.minRows = -nrRows;
        this.maxRows = nrRows;

        this.minimumRenderZ = -nrRows - (256 - py);
        this.maximumRenderZ = nrRows + py;

        this.blockBounds.update(this.facing,
                this.minCols, this.minimumRenderZ, this.minRows,
                this.maxCols, this.maximumRenderZ, this.maxRows);
        this.blockBounds.offset(this.startBlock);

        if (renderMode == RenderMode.FROM_CACHE && this.properties.get("finishedRendering", false)) {
            this.currentRenderZ = this.maximumRenderZ;
        } else {
            this.currentRenderZ = this.minimumRenderZ;
            this.properties.set("finishedRendering", false);
        }

        this.forwardRenderNeeded = true;
        this.dirtyTiles.clear();

        // Reset drawn tiles state when initializing / from cache
        // This will cause everything to render again
        if (renderMode != RenderMode.TRANSLATION) {
            int len = (this.maxRows - this.minRows + 1) * (this.maxCols - this.minCols + 1);
            if (this.drawnTiles == null || len != this.drawnTiles.length) {
                this.drawnTiles = new boolean[len];
            } else {
                Arrays.fill(this.drawnTiles, false);
            }
        }

        rendertime = 0;
    }

    public boolean isRenderingWorld(World world) {
        return this.startBlock.getWorld() == world;
    }

    public void onBlockChange(Block block) {
        onBlockChange(block.getWorld(), block.getX(), block.getY(), block.getZ());
    }

    public void onBlockChange(World world, int bx, int by, int bz) {
        // Check possibly in range before doing computationally expensive stuff
        if (world == this.startBlock.getWorld() && this.blockBounds.contains(bx, by, bz)) {
            int dx = bx - this.startBlock.getX();
            int dy = by - this.startBlock.getY();
            int dz = bz - this.startBlock.getZ();
            IntVector3 tile = MapUtil.blockToScreenTile(this.facing, dx, dy, dz);
            if (tile == null || tile.x < this.minCols || tile.x > this.maxCols || tile.y < this.minRows || tile.y > this.maxRows) {
                return;
            }
            this.dirtyTiles.add(tile);
        }
    }

    private void invalidateTile(int tx, int ty, int tz) {
        if (tx >= this.minCols && tx <= this.maxCols && ty >= this.minRows && ty <= this.maxRows) {
            tx -= this.minCols;
            ty -= this.minRows;
            this.drawnTiles[ty * (this.maxCols - this.minCols + 1) + tx] = false;
            this.forwardRenderNeeded = true;
            if (this.currentRenderZ > tz) {
                this.currentRenderZ = tz;
            }
        }
    }

    @Override
    public void onKeyPressed(MapKeyEvent event) {
        if (this.menuShowTicks > 0) {
            this.showMenu(); // keep on while interacted

            // Menu is shown. Intercepts keys.
            if (event.getKey() == Key.LEFT) {
                // Previous button
                this.setMenuIndex(this.menuSelectIndex - 1);
            } else if (event.getKey() == Key.RIGHT) {
                // Next button
                this.setMenuIndex(this.menuSelectIndex + 1);
            } else if (event.getKey() == Key.ENTER) {
                // Activate menu button
                this.menuButtons[this.menuSelectIndex].onPressed();
            } else if (event.getKey() == Key.BACK) {
                // Hide menu again
                this.hideMenu();
            }
            
        } else if (event.getKey() == Key.BACK) {
            // Show menu
            this.showMenu();
        } else {
            // No menu is shown. Simple navigation.
            if (event.getKey() == Key.UP) {
                BlockFace facing_fwd = facing;
                moveTiles(0, 2, facing_fwd);
            } else if (event.getKey() == Key.RIGHT) {
                BlockFace facing_rgt = FaceUtil.rotate(facing, 2);
                moveTiles(-2, 0, facing_rgt);
            } else if (event.getKey() == Key.DOWN) {
                BlockFace facing_bwd = FaceUtil.rotate(facing, 4);
                moveTiles(0, -2, facing_bwd);
            } else if (event.getKey() == Key.LEFT) {
                BlockFace facing_lft = FaceUtil.rotate(facing, 6);
                moveTiles(2, 0, facing_lft);
            }

            if (event.getKey() == Key.ENTER) {
                //rotate(1);
                this.render(RenderMode.INITIALIZE);
            }
        }
    }

    public void moveTiles(int dtx, int dty, BlockFace blockChange) {
        // Move all pixels
        getLayer().movePixels(
                zoom.getScreenX(dtx) - zoom.getScreenX(0),
                zoom.getScreenY(dty) - zoom.getScreenY(0)
        );

        // Transform the "have we drawn this tile?" buffer with the same movement
        // Shorten the direction moved away from, since some of those blocks were only partially drawn
        // They will have to be fully re-drawn to draw the cut-off portion
        int fMinCols = this.minCols;
        int fMaxCols = this.maxCols;
        int fMinRows = this.minRows;
        int fMaxRows = this.maxRows;
        if (dtx > 0) {
            fMinCols += dtx + 1;
        } else if (dtx < 0) {
            fMaxCols += dtx - 1;
        }
        if (dty > 0) {
            fMinRows += dty + 7;
        } else if (dty < 0) {
            fMaxRows += dty - 5;
        }

        boolean[] newDrawnTiles = new boolean[this.drawnTiles.length];
        int tileIdx = -1;
        for (int y = this.minRows; y <= this.maxRows; y++) {
            for (int x = this.minCols; x <= this.maxCols; x++) {
                tileIdx++;
                boolean value = this.drawnTiles[tileIdx];
                int mx = x + dtx;
                int my = y + dty;
                if (mx >= fMinCols && mx <= fMaxCols && my >= fMinRows && my <= fMaxRows) {
                    int index = tileIdx;
                    index += dty * (this.maxCols - this.minCols + 1) + dtx;
                    newDrawnTiles[index] = value;
                }
            }
        }
        this.drawnTiles = newDrawnTiles;

        // When moving up or down, the depth buffer values are incremented/decremented
        if (dty != 0) {
            short[] buffer = this.getLayer().getDepthBuffer();
            for (int i = 0; i < buffer.length; i++) {
                if (buffer[i] != MapCanvas.MAX_DEPTH) {
                    buffer[i] -= dty;
                }
            }
        }

        // Re-render with the changed block position
        properties.set("px", properties.get("px", 0) + blockChange.getModX());
        properties.set("pz", properties.get("pz", 0) + blockChange.getModZ());

        render(RenderMode.TRANSLATION);
    }

    private int getLight(int x, int y, int z) {
        return WorldUtil.getSkyLight(this.startBlock.getWorld().getChunkAt(x >> 4, z >> 4), x, y, z);
    }

    /**
     * Draws a block at particular tile coordinates. The draw depth must have been set to
     * the tile coordinate z (depth) before drawing.
     * 
     * @param tx Tile x-coordinate (horizontal)
     * @param ty Tile y-coordinate (vertical)
     * @param tz Tile depth
     * @param isRedraw Whether to redraw the block entirely, instead of on top the current contents
     * @return True if void area remains behind the block drawn, False if no more drawing is required
     */
    public boolean drawBlockTile(int tx, int ty, int tz, boolean isRedraw) {
        IntVector3 b = MapUtil.screenTileToBlock(this.facing, tx, ty, tz);
        if (b != null) {
            return drawBlockAtTile(b, tx, ty, isRedraw);
        } else {
            return true;
        }
    }

    /**
     * Draws a block at particular tile coordinates. The draw depth must have been set to
     * the tile coordinate z (depth) before drawing.
     * 
     * @param relativeBlockCoords Coordinates relative to start block to draw
     * @param tx Tile x-coordinate (horizontal)
     * @param ty Tile y-coordinate (vertical)
     * @param isRedraw Whether to redraw the block entirely, instead of on top the current contents
     * @return True if void area remains behind the block drawn, False if no more drawing is required
     */
    public boolean drawBlockAtTile(IntVector3 relativeBlockCoords, int tx, int ty, boolean isRedraw) {
        int x = this.startBlock.getX() + relativeBlockCoords.x;
        int y = this.startBlock.getY() + relativeBlockCoords.y;
        int z = this.startBlock.getZ() + relativeBlockCoords.z;
        if (y >= 0 && y < 256) {
            MapTexture sprite = this.sprites.getSprite(this.startBlock.getWorld(), x, y, z);
            if (sprite != this.sprites.AIR || !isRedraw) {
                int draw_x = sprites.getZoom().getDrawX(tx) + (this.getWidth() >> 1);
                int draw_y = sprites.getZoom().getDrawY(ty) + (this.getHeight() >> 1);

                if (enableLight) {
                    int light = getLight(x, y + 1, z);
                    if (light < 15) {
                        light = Math.max(light, getLight(x - 1, y, z));
                        light = Math.max(light, getLight(x + 1, y, z));
                        light = Math.max(light, getLight(x, y, z - 1));
                        light = Math.max(light, getLight(x, y, z + 1));
                    }

                    float lightf = (float) light / 15.0f;
                    lightf *= lightf;

                    byte[] in = sprite.getBuffer();
                    byte[] buff = testSprite.getBuffer();
                    for (int i = 0; i < buff.length; i++) {
                        buff[i] = MapColorPalette.getSpecular(in[i], lightf);
                    }

                    getLayer().draw(testSprite, draw_x, draw_y);
                } else {
                    getLayer().draw(sprite, draw_x, draw_y);
                }

                return getLayer().hasMoreDepth(draw_x, draw_y, sprite.getWidth(), sprite.getHeight());
            } else {
                return true;
            }
        } else {
            return true;
        }
    }

    public void hideMenu() {
        menuShowTicks = 0;
        for (MenuButton button : this.menuButtons) {
            button.setVisible(false);
        }
        getLayer(1).clearRectangle(0, 0, this.menu_bg.getWidth(), this.menu_bg.getHeight());
    }

    public void showMenu() {
        menuShowTicks = MENU_DURATION;
        for (int i = 0; i < this.menuButtons.length; i++) {
            this.menuButtons[i].setVisible(true);
            this.menuButtons[i].setSelected(i == this.menuSelectIndex);
        }
        getLayer(1).draw(this.menu_bg, 0, 0);
    }

    public void setMenuIndex(int index) {
        while (index < 0) {
            index += this.menuButtons.length;
        }
        while (index >= this.menuButtons.length) {
            index -= this.menuButtons.length;
        }
        if (menuSelectIndex != index) {
            menuSelectIndex = index;
            for (int i = 0; i < this.menuButtons.length; i++) {
                this.menuButtons[i].setSelected(i == index);
            }
        }
    }

    public void zoom(int n) {
        ZoomLevel level = properties.get("zoom", ZoomLevel.DEFAULT);
        ZoomLevel[] values = ZoomLevel.values();
        int zoomLevelIdx = 0;
        while (values[zoomLevelIdx] != level) {
            zoomLevelIdx++;
        }
        zoomLevelIdx = MathUtil.clamp(zoomLevelIdx + n, 0, values.length - 1);
        properties.set("zoom", values[zoomLevelIdx]);
        this.render(RenderMode.INITIALIZE);
    }

    public void rotate(int n) {
        BlockFace facing = properties.get("facing", BlockFace.NORTH_EAST);
        facing = FaceUtil.rotate(facing, n * 2);
        properties.set("facing", facing);
        this.render(RenderMode.INITIALIZE);
    }

    /**
     * Gets the start block, which is the block the player was at when activating this maplands map.
     * 
     * @return start block
     */
    public Block getStartBlock() {
        return this.startBlock;
    }

    /**
     * Gets the facing direction when rendering the map. Is always either NORTH_EAST,
     * NORTH_WEST, SOUTH_EAST or SOUTH_WEST.
     * 
     * @return facing direction
     */
    public BlockFace getFacing() {
        return this.facing;
    }

    /**
     * Gets the exact tile coordinates of particular pixel coordinates
     * 
     * @param x - pixel coordinate
     * @param y - pixel coordinate
     * @return tile coordinates. Null if there is no tile here.
     */
    public IntVector3 getTileAt(int x, int y) {
        int z = getLayer().getDepth(x, y);
        if (z == MapCanvas.MAX_DEPTH) {
            // No tile drawn here
            return null;
        }
        return this.zoom.screenToTile(new IntVector3(x - (this.getWidth() >> 1), y - (this.getHeight() >> 1), z));
    }

    /**
     * Gets the exact block displayed at particular pixel coordinates
     * 
     * @param x - pixel coordinate (horizontal)
     * @param y - pixel coordinate (vertical)
     * @return block at these coordinates. Null when clicking outside the canvas or in the void.
     */
    public Block getBlockAt(int x, int y) {
        IntVector3 tile = this.getTileAt(x, y);
        if (tile == null) {
            return null;
        } else {
            IntVector3 relativeBlockCoords = MapUtil.screenTileToBlock(this.facing, tile.x, tile.y, tile.z);
            return this.startBlock.getRelative(relativeBlockCoords.x, relativeBlockCoords.y, relativeBlockCoords.z);
        }
    }

    /**
     * Renders a single depth level onto the canvas
     * 
     * @param depth The depth to render (same as z-coordinate of the tile)
     */
    private void renderSlice(int depth) {
        getLayer().setDrawDepth(depth);
        int tileIdx = -1;
        for (int ty = minRows; ty <= maxRows; ty++) {
            int ymin = ty - (this.startBlock.getY());
            int ymax = ty + (256 - this.startBlock.getY());
            if (ty < ymin || ty > ymax) {
                continue;
            }

            for (int tx = minCols; tx <= maxCols; tx++) {
                tileIdx++;
                if (!this.drawnTiles[tileIdx] && !drawBlockTile(tx, ty, depth, true)) {

                    // Fully covered. No longer render this tile!
                    this.drawnTiles[tileIdx] = true;
                }
            }
        }
    }

    @Override
    public void onRightClick(MapClickEvent event) {
        event.setCancelled(true);
    }

    @Override
    public void onTick() {
        if (menuShowTicks > 0 && --menuShowTicks == 0) {
            this.hideMenu();
        }
        for (MenuButton button : this.menuButtons) {
            button.onTick();
        }

        // Re-render all dirty tiles
        // If they result in holes, schedule the area behind for re-rendering
        if (!dirtyTiles.isEmpty()) {
            for (IntVector3 tile : this.dirtyTiles) {
                getLayer().setDrawDepth(tile.z);
                if (drawBlockTile(tile.x, tile.y, tile.z, false)) {
                    for (int dtx = -1; dtx <= 1; dtx++) {
                        for (int dty = -2; dty <= 2; dty++) {
                            invalidateTile(tile.x + dtx, tile.y + dty, tile.z);
                        }
                    }
                }
            }
            this.dirtyTiles.clear();
        }

        // Render at most 50 ms / map / tick
        long startTime = System.currentTimeMillis();
        if (this.currentRenderZ <= this.maximumRenderZ) {
            rendertime++;
            do {
                if (forwardRenderNeeded) {
                    renderSlice(currentRenderZ);
                    forwardRenderNeeded = this.getLayer().hasMoreDepth();
                }
            } while (++this.currentRenderZ <= this.maximumRenderZ && (System.currentTimeMillis() - startTime) < Maplands.getMaxRenderTime());

            if (this.currentRenderZ > this.maximumRenderZ) {
                // Fill all remaining holes with the desired background color
                for (int x = 0; x < this.getWidth(); x++) {
                    for (int y = 0; y < this.getHeight(); y++) {
                        if (this.getLayer().getDepth(x, y) == MapCanvas.MAX_DEPTH) {
                            this.getLayer().writePixel(x, y, Maplands.getBackgroundColor());
                        }
                    }
                }

                // Store in attributes that it has finished rendering
                if (!properties.get("finishedRendering", false)) {
                    properties.set("finishedRendering", true);
                    Maplands.plugin.getCache().save(this.getMapInfo().uuid, this.getLayer());
                }

                //System.out.println("RENDER TIME: " + rendertime);
            }
        }
    }

    // Refreshed automatically and cached
    // It is used very often to handle block physics; this makes this faster
    private static Collection<MaplandsDisplay> all_maplands_displays = Collections.emptySet();
    private static Task refresh_mapdisplays_task = null;
    private static void refreshMapDisplayLookup() {
        // Refresh now, and again one tick delayed, to be sure it is updated.
        all_maplands_displays = MapDisplay.getAllDisplays(MaplandsDisplay.class);
        if (refresh_mapdisplays_task == null) {
            refresh_mapdisplays_task = new Task(Maplands.plugin) {
                @Override
                public void run() {
                    all_maplands_displays = MapDisplay.getAllDisplays(MaplandsDisplay.class);
                    refresh_mapdisplays_task = null;
                }
            };
        }
    }

    public static Collection<MaplandsDisplay> getAllDisplays() {
        return all_maplands_displays;
    }
}
