package grondag.canvas.terrain.occlusion;

import static grondag.canvas.terrain.occlusion.Constants.CAMERA_PRECISION_BITS;
import static grondag.canvas.terrain.occlusion.Constants.CAMERA_PRECISION_UNITY;
import static grondag.canvas.terrain.occlusion.Constants.DOWN;
import static grondag.canvas.terrain.occlusion.Constants.EAST;
import static grondag.canvas.terrain.occlusion.Constants.EMPTY_BITS;
import static grondag.canvas.terrain.occlusion.Constants.NORTH;
import static grondag.canvas.terrain.occlusion.Constants.PIXEL_HEIGHT;
import static grondag.canvas.terrain.occlusion.Constants.PIXEL_WIDTH;
import static grondag.canvas.terrain.occlusion.Constants.SOUTH;
import static grondag.canvas.terrain.occlusion.Constants.TILE_COUNT;
import static grondag.canvas.terrain.occlusion.Constants.UP;
import static grondag.canvas.terrain.occlusion.Constants.V000;
import static grondag.canvas.terrain.occlusion.Constants.V001;
import static grondag.canvas.terrain.occlusion.Constants.V010;
import static grondag.canvas.terrain.occlusion.Constants.V011;
import static grondag.canvas.terrain.occlusion.Constants.V100;
import static grondag.canvas.terrain.occlusion.Constants.V101;
import static grondag.canvas.terrain.occlusion.Constants.V110;
import static grondag.canvas.terrain.occlusion.Constants.V111;
import static grondag.canvas.terrain.occlusion.Constants.WEST;
import static grondag.canvas.terrain.occlusion.Data.forceRedraw;
import static grondag.canvas.terrain.occlusion.Data.needsRedraw;
import static grondag.canvas.terrain.occlusion.Data.occluderVersion;
import static grondag.canvas.terrain.occlusion.Data.offsetX;
import static grondag.canvas.terrain.occlusion.Data.offsetY;
import static grondag.canvas.terrain.occlusion.Data.offsetZ;
import static grondag.canvas.terrain.occlusion.Data.viewX;
import static grondag.canvas.terrain.occlusion.Data.viewY;
import static grondag.canvas.terrain.occlusion.Data.viewZ;
import static grondag.canvas.terrain.occlusion.Rasterizer.drawQuad;
import static grondag.canvas.terrain.occlusion.Rasterizer.testQuad;

import java.io.File;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import grondag.canvas.CanvasMod;
import grondag.canvas.mixinterface.Matrix4fExt;
import grondag.canvas.render.CanvasFrustum;
import grondag.canvas.terrain.BuiltRenderRegion;
import grondag.canvas.terrain.occlusion.region.PackedBox;

public class TerrainOccluder {
	private final Matrix4L baseMvpMatrix = new Matrix4L();

	/**
	 * Previously tested regions can reuse test results if their version matches.
	 * However, they must still be drawn (if visible) if indicated by {@link #clearSceneIfNeeded(int, int)}.
	 */
	public int version() {
		return occluderVersion.get();
	}

	/**
	 * Force update to new version if provided version matches current
	 * @param occluderVersion
	 */
	public void invalidate(int invalidVersion) {
		if (occluderVersion.compareAndSet(invalidVersion, invalidVersion + 1))  {
			forceRedraw = true;
		}
	}

	/**
	 * Force update to new version
	 */
	public void invalidate() {
		occluderVersion.incrementAndGet();
		forceRedraw = true;
	}

	public void prepareRegion(BlockPos origin, int occlusionRange) {
		Data.occlusionRange = occlusionRange;

		// PERF: could perhaps reuse CameraRelativeCenter values in BuildRenderRegion that are used by Frustum
		offsetX = (int) ((origin.getX() << CAMERA_PRECISION_BITS) - viewX);
		offsetY = (int) ((origin.getY() << CAMERA_PRECISION_BITS) - viewY);
		offsetZ = (int) ((origin.getZ() << CAMERA_PRECISION_BITS) - viewZ);

		final Matrix4L mvpMatrix = Data.mvpMatrix;
		mvpMatrix.copyFrom(baseMvpMatrix);
		mvpMatrix.translate(offsetX, offsetY, offsetZ, CAMERA_PRECISION_BITS);
	}

	public void outputRaster() {
		final long t = System.currentTimeMillis();

		if (t >= Rasterizer.nextRasterOutputTime) {
			Rasterizer.nextRasterOutputTime = t + 1000;

			final NativeImage nativeImage = new NativeImage(PIXEL_WIDTH, PIXEL_HEIGHT, false);

			for (int x = 0; x < PIXEL_WIDTH; x++) {
				for (int y = 0; y < PIXEL_HEIGHT; y++) {
					nativeImage.setPixelColor(x, y, Rasterizer.testPixel(x, y) ? -1 :0xFF000000);
				}
			}

			nativeImage.mirrorVertically();

			@SuppressWarnings("resource")
			final File file = new File(MinecraftClient.getInstance().runDirectory, "canvas_occlusion_raster.png");

			Util.method_27958().execute(() -> {
				try {
					nativeImage.writeFile(file);
				} catch (final Exception e) {
					CanvasMod.LOG.warn("Couldn't save occluder image", e);
				} finally {
					nativeImage.close();
				}

			});
		}
	}


	/**
	 * Check if needs redrawn and prep for redraw if  so.
	 * When false, regions should be drawn only if their occluder version is not current.
	 *
	 * Also checks for invalidation of occluder version using positionVersion.
	 *
	 * @param projectionMatrix
	 * @param modelMatrix
	 * @param camera
	 * @param frustum
	 * @param regionVersion  Needed because chunk camera position update whenever a chunk boundary is crossed by Frustum doesn't care.
	 */
	public void prepareScene(Camera camera, CanvasFrustum frustum, int regionVersion) {
		final int viewVersion = frustum.viewVersion();
		final int positionVersion = frustum.positionVersion();

		if (Data.viewVersion != viewVersion) {
			final Matrix4L baseMvpMatrix = this.baseMvpMatrix;
			final Matrix4L tempMatrix = Data.mvpMatrix;
			final Matrix4fExt projectionMatrix = frustum.projectionMatrix();
			final Matrix4fExt modelMatrix = frustum.modelMatrix();

			baseMvpMatrix.loadIdentity();

			tempMatrix.copyFrom(projectionMatrix);
			baseMvpMatrix.multiply(tempMatrix);

			tempMatrix.copyFrom(modelMatrix);
			baseMvpMatrix.multiply(tempMatrix);

			final Vec3d vec3d = camera.getPos();
			viewX = Math.round(vec3d.getX() * CAMERA_PRECISION_UNITY);
			viewY = Math.round(vec3d.getY() * CAMERA_PRECISION_UNITY);
			viewZ = Math.round(vec3d.getZ() * CAMERA_PRECISION_UNITY);
		}

		if (forceRedraw) {
			Data.viewVersion = viewVersion;
			Data.positionVersion = positionVersion;
			Data.regionVersion = regionVersion;
			System.arraycopy(EMPTY_BITS, 0, Data.tiles, 0, TILE_COUNT);
			forceRedraw = false;
			needsRedraw = true;
		} else if (Data.positionVersion != positionVersion || Data.regionVersion != regionVersion) {
			occluderVersion.incrementAndGet();
			Data.viewVersion = viewVersion;
			Data.positionVersion = positionVersion;
			Data.regionVersion = regionVersion;
			System.arraycopy(EMPTY_BITS, 0, Data.tiles, 0, TILE_COUNT);
			needsRedraw = true;
		} else if (Data.viewVersion != viewVersion) {
			Data.viewVersion = viewVersion;
			System.arraycopy(EMPTY_BITS, 0, Data.tiles, 0, TILE_COUNT);
			needsRedraw = true;
		} else {
			needsRedraw = false;
		}


	}

	public boolean needsRedraw() {
		return needsRedraw;
	}

	/**
	 * Does not rely on winding order but instead the distance from
	 * plane with known facing to camera position.
	 */
	public boolean isBoxVisible(int packedBox) {
		final int x0  = PackedBox.x0(packedBox) - 1;
		final int y0  = PackedBox.y0(packedBox) - 1;
		final int z0  = PackedBox.z0(packedBox) - 1;
		final int x1  = PackedBox.x1(packedBox) + 1;
		final int y1  = PackedBox.y1(packedBox) + 1;
		final int z1  = PackedBox.z1(packedBox) + 1;

		final int offsetX = Data.offsetX;
		final int offsetY = Data.offsetY;
		final int offsetZ = Data.offsetZ;

		int outcome = 0;

		// if camera below top face can't be seen
		if (offsetY < -(y1 << CAMERA_PRECISION_BITS)) {
			outcome |= UP;
		} else if (offsetY > -(y0 << CAMERA_PRECISION_BITS)) {
			outcome |= DOWN;
		}

		if (offsetX < -(x1 << CAMERA_PRECISION_BITS)) {
			outcome |= EAST;
		} else if (offsetX > -(x0 << CAMERA_PRECISION_BITS)) {
			outcome |= WEST;
		}

		if (offsetZ < -(z1 << CAMERA_PRECISION_BITS)) {
			outcome |= SOUTH;
		} else if (offsetZ > -(z0 << CAMERA_PRECISION_BITS)) {
			outcome |= NORTH;
		}

		return BOX_TESTS[outcome].apply(x0, y0, z0, x1, y1, z1);
	}

	/**
	 * Does not rely on winding order but instead the distance from
	 * plane with known facing to camera position.
	 */
	private void occludeInner(int packedBox) {
		final int x0  = PackedBox.x0(packedBox);
		final int y0  = PackedBox.y0(packedBox);
		final int z0  = PackedBox.z0(packedBox);
		final int x1  = PackedBox.x1(packedBox);
		final int y1  = PackedBox.y1(packedBox);
		final int z1  = PackedBox.z1(packedBox);

		final int offsetX = Data.offsetX;
		final int offsetY = Data.offsetY;
		final int offsetZ = Data.offsetZ;

		int outcome = 0;

		// if camera below top face can't be seen
		if (offsetY < -(y1 << CAMERA_PRECISION_BITS)) {
			outcome |= UP;
		} else if (offsetY > -(y0 << CAMERA_PRECISION_BITS)) {
			outcome |= DOWN;
		}

		if (offsetX < -(x1 << CAMERA_PRECISION_BITS)) {
			outcome |= EAST;
		} else if (offsetX > -(x0 << CAMERA_PRECISION_BITS)) {
			outcome |= WEST;
		}

		if (offsetZ < -(z1 << CAMERA_PRECISION_BITS)) {
			outcome |= SOUTH;
		} else if (offsetZ > -(z0 << CAMERA_PRECISION_BITS)) {
			outcome |= NORTH;
		}

		BOX_DRAWS[outcome].apply(x0, y0, z0, x1, y1, z1);
	}

	public void occlude(int[] visData) {
		final int occlusionRange = Data.occlusionRange;
		final int limit= visData.length;

		if (limit > 1) {
			for (int i = 1; i < limit; i++) {
				final int box  = visData[i];
				if (occlusionRange > PackedBox.range(box)) {
					break;
				}

				occludeInner(box);
			}
		}
	}

	/**
	 * Returns value with face flags set when all such
	 * faces in the region are at least 64 blocks away camera.
	 * @param region
	 * @return
	 */
	int backfaceVisibilityFlags(BuiltRenderRegion region) {
		final int offsetX = Data.offsetX;
		final int offsetY = Data.offsetY;
		final int offsetZ = Data.offsetZ;

		int outcome = 0;

		// if offsetY is positive, chunk origin is above camera
		// if offsetY is negative, chunk origin is below camera;
		/**
		 * offsets are origin - camera
		 * if looking directly at chunk center, two values will be -8
		 *
		 * pos face check: -8 < -(16) == false
		 * neg face check: -8 > -(0) == false
		 *
		 * if 32 blocks above/positive to origin two values will be -32
		 *
		 * pos face check: -32 < -(16) == true
		 * neg face check: -32 > -(0) == false
		 *
		 * if 32 blocks below/positive to origin two values will be 32
		 *
		 * pos face check: 32 < -(16) == false
		 * neg face check: 32 > -(0) == true
		 *
		 *
		 * if looking directly at chunk center, two values will be -8
		 *
		 * pos face check: -8 < -(16) == false
		 * neg face check: -8 > -(0) == false
		 *
		 * if 64 blocks above/positive to origin two values will be -64
		 * neg face check: -64 > -(16) == false
		 *
		 * neg face > -64
		 *
		 * if 64 blocks below/positive to origin two values will be 64
		 *
		 * pos face check: 64 < -16 == false
		 *
		 * pos face culled when offset > 48
		 * neg face culled when offset < -72
		 *
		 *
		 * pos face visible when offset <= 48
		 * neg face visible when offset >= -72
		 */
		if (offsetY < (48 << CAMERA_PRECISION_BITS)) {
			outcome |= UP;
		} else if (offsetY > -(72 << CAMERA_PRECISION_BITS)) {
			outcome |= DOWN;
		}

		if (offsetX < (48 << CAMERA_PRECISION_BITS)) {
			outcome |= EAST;
		} else if (offsetX > -(72 << CAMERA_PRECISION_BITS)) {
			outcome |= WEST;
		}

		if (offsetZ < (48 << CAMERA_PRECISION_BITS)) {
			outcome |= SOUTH;
		} else if (offsetZ > -(72 << CAMERA_PRECISION_BITS)) {
			outcome |= NORTH;
		}

		return outcome;
	}

	@FunctionalInterface interface BoxTest {
		boolean apply(int x0, int y0, int z0, int x1, int y1, int z1);
	}

	@FunctionalInterface interface BoxDraw {
		void apply(int x0, int y0, int z0, int x1, int y1, int z1);
	}

	private static BoxTest[] BOX_TESTS = new BoxTest[128];
	private static BoxDraw[] BOX_DRAWS = new BoxDraw[128];

	static {
		BOX_TESTS[0] = (x0, y0, z0, x1, y1, z1) -> {
			return false;
		};

		BOX_TESTS[UP] = (x0, y0, z0, x1, y1, z1) -> {
			Rasterizer.setupVertex(V010, x0, y1, z0);
			Rasterizer.setupVertex(V011, x0, y1, z1);
			Rasterizer.setupVertex(V110, x1, y1, z0);
			Rasterizer.setupVertex(V111, x1, y1, z1);
			return testQuad(V110, V010, V011, V111);
		};

		BOX_TESTS[DOWN] = (x0, y0, z0, x1, y1, z1) -> {
			Rasterizer.setupVertex(V000, x0, y0, z0);
			Rasterizer.setupVertex(V001, x0, y0, z1);
			Rasterizer.setupVertex(V100, x1, y0, z0);
			Rasterizer.setupVertex(V101, x1, y0, z1);
			return testQuad(V000, V100, V101, V001);
		};

		BOX_TESTS[EAST] = (x0, y0, z0, x1, y1, z1) -> {
			Rasterizer.setupVertex(V100, x1, y0, z0);
			Rasterizer.setupVertex(V101, x1, y0, z1);
			Rasterizer.setupVertex(V110, x1, y1, z0);
			Rasterizer.setupVertex(V111, x1, y1, z1);
			return testQuad(V101, V100, V110, V111);
		};

		BOX_TESTS[WEST] = (x0, y0, z0, x1, y1, z1) -> {
			Rasterizer.setupVertex(V000, x0, y0, z0);
			Rasterizer.setupVertex(V001, x0, y0, z1);
			Rasterizer.setupVertex(V010, x0, y1, z0);
			Rasterizer.setupVertex(V011, x0, y1, z1);
			return testQuad(V000, V001, V011, V010);
		};

		BOX_TESTS[NORTH] = (x0, y0, z0, x1, y1, z1) -> {
			Rasterizer.setupVertex(V000, x0, y0, z0);
			Rasterizer.setupVertex(V010, x0, y1, z0);
			Rasterizer.setupVertex(V100, x1, y0, z0);
			Rasterizer.setupVertex(V110, x1, y1, z0);
			return testQuad(V100, V000, V010, V110);
		};

		BOX_TESTS[SOUTH] = (x0, y0, z0, x1, y1, z1) -> {
			Rasterizer.setupVertex(V001, x0, y0, z1);
			Rasterizer.setupVertex(V011, x0, y1, z1);
			Rasterizer.setupVertex(V101, x1, y0, z1);
			Rasterizer.setupVertex(V111, x1, y1, z1);
			return testQuad(V001, V101, V111, V011);
		};

		// NB: Split across two quads to give more evenly-sized test regions vs potentially one big and one very small
		BOX_TESTS[UP | EAST] = (x0, y0, z0, x1, y1, z1) -> {
			Rasterizer.setupVertex(V010, x0, y1, z0);
			Rasterizer.setupVertex(V011, x0, y1, z1);
			Rasterizer.setupVertex(V100, x1, y0, z0);
			Rasterizer.setupVertex(V101, x1, y0, z1);
			Rasterizer.setupVertex(V110, x1, y1, z0);
			Rasterizer.setupVertex(V111, x1, y1, z1);
			return testQuad(V010, V011, V111, V101) ||
					testQuad(V101, V100, V110, V010);
		};

		BOX_TESTS[UP | WEST] = (x0, y0, z0, x1, y1, z1) -> {
			Rasterizer.setupVertex(V000, x0, y0, z0);
			Rasterizer.setupVertex(V001, x0, y0, z1);
			Rasterizer.setupVertex(V010, x0, y1, z0);
			Rasterizer.setupVertex(V011, x0, y1, z1);
			Rasterizer.setupVertex(V110, x1, y1, z0);
			Rasterizer.setupVertex(V111, x1, y1, z1);
			return testQuad(V111, V110, V010, V000) ||
					testQuad(V000, V001, V011, V111);
		};

		BOX_TESTS[UP | NORTH] = (x0, y0, z0, x1, y1, z1) -> {
			Rasterizer.setupVertex(V000, x0, y0, z0);
			Rasterizer.setupVertex(V010, x0, y1, z0);
			Rasterizer.setupVertex(V011, x0, y1, z1);
			Rasterizer.setupVertex(V100, x1, y0, z0);
			Rasterizer.setupVertex(V110, x1, y1, z0);
			Rasterizer.setupVertex(V111, x1, y1, z1);
			return testQuad(V011, V111, V110, V100) ||
					testQuad(V100, V000, V010, V011);
		};

		BOX_TESTS[UP | SOUTH] = (x0, y0, z0, x1, y1, z1) -> {
			Rasterizer.setupVertex(V001, x0, y0, z1);
			Rasterizer.setupVertex(V010, x0, y1, z0);
			Rasterizer.setupVertex(V011, x0, y1, z1);
			Rasterizer.setupVertex(V101, x1, y0, z1);
			Rasterizer.setupVertex(V110, x1, y1, z0);
			Rasterizer.setupVertex(V111, x1, y1, z1);
			return testQuad(V110, V010, V011, V001) ||
					testQuad(V001, V101, V111, V110);
		};

		BOX_TESTS[DOWN | EAST] = (x0, y0, z0, x1, y1, z1) -> {
			Rasterizer.setupVertex(V000, x0, y0, z0);
			Rasterizer.setupVertex(V001, x0, y0, z1);
			Rasterizer.setupVertex(V100, x1, y0, z0);
			Rasterizer.setupVertex(V101, x1, y0, z1);
			Rasterizer.setupVertex(V110, x1, y1, z0);
			Rasterizer.setupVertex(V111, x1, y1, z1);
			return testQuad(V001, V000, V100, V110) ||
					testQuad(V110, V111, V101, V001);
		};

		BOX_TESTS[DOWN | WEST] = (x0, y0, z0, x1, y1, z1) -> {
			Rasterizer.setupVertex(V000, x0, y0, z0);
			Rasterizer.setupVertex(V001, x0, y0, z1);
			Rasterizer.setupVertex(V010, x0, y1, z0);
			Rasterizer.setupVertex(V011, x0, y1, z1);
			Rasterizer.setupVertex(V100, x1, y0, z0);
			Rasterizer.setupVertex(V101, x1, y0, z1);
			return testQuad(V100, V101, V001, V011) ||
					testQuad(V011, V010, V000, V100);
		};

		BOX_TESTS[DOWN | NORTH] = (x0, y0, z0, x1, y1, z1) -> {
			Rasterizer.setupVertex(V000, x0, y0, z0);
			Rasterizer.setupVertex(V001, x0, y0, z1);
			Rasterizer.setupVertex(V010, x0, y1, z0);
			Rasterizer.setupVertex(V100, x1, y0, z0);
			Rasterizer.setupVertex(V101, x1, y0, z1);
			Rasterizer.setupVertex(V110, x1, y1, z0);
			return testQuad(V101, V001, V000, V010) ||
					testQuad(V010, V110, V100, V101);
		};

		BOX_TESTS[DOWN | SOUTH] = (x0, y0, z0, x1, y1, z1) -> {
			Rasterizer.setupVertex(V000, x0, y0, z0);
			Rasterizer.setupVertex(V001, x0, y0, z1);
			Rasterizer.setupVertex(V011, x0, y1, z1);
			Rasterizer.setupVertex(V100, x1, y0, z0);
			Rasterizer.setupVertex(V101, x1, y0, z1);
			Rasterizer.setupVertex(V111, x1, y1, z1);
			return testQuad(V000, V100, V101, V111) ||
					testQuad(V111, V011, V001, V000);
		};

		BOX_TESTS[NORTH | EAST] = (x0, y0, z0, x1, y1, z1) -> {
			Rasterizer.setupVertex(V000, x0, y0, z0);
			Rasterizer.setupVertex(V010, x0, y1, z0);
			Rasterizer.setupVertex(V100, x1, y0, z0);
			Rasterizer.setupVertex(V101, x1, y0, z1);
			Rasterizer.setupVertex(V110, x1, y1, z0);
			Rasterizer.setupVertex(V111, x1, y1, z1);
			return testQuad(V000, V010, V110, V111) ||
					testQuad(V111, V101, V100, V000);
		};

		BOX_TESTS[NORTH | WEST] = (x0, y0, z0, x1, y1, z1) -> {
			Rasterizer.setupVertex(V000, x0, y0, z0);
			Rasterizer.setupVertex(V001, x0, y0, z1);
			Rasterizer.setupVertex(V010, x0, y1, z0);
			Rasterizer.setupVertex(V011, x0, y1, z1);
			Rasterizer.setupVertex(V100, x1, y0, z0);
			Rasterizer.setupVertex(V110, x1, y1, z0);
			return testQuad(V110, V100, V000, V001) ||
					testQuad(V001, V011, V010, V110);
		};

		BOX_TESTS[SOUTH | EAST] = (x0, y0, z0, x1, y1, z1) -> {
			Rasterizer.setupVertex(V001, x0, y0, z1);
			Rasterizer.setupVertex(V011, x0, y1, z1);
			Rasterizer.setupVertex(V100, x1, y0, z0);
			Rasterizer.setupVertex(V101, x1, y0, z1);
			Rasterizer.setupVertex(V110, x1, y1, z0);
			Rasterizer.setupVertex(V111, x1, y1, z1);
			return testQuad(V011, V001, V101, V100) ||
					testQuad(V100, V110, V111, V011);
		};

		BOX_TESTS[SOUTH | WEST] = (x0, y0, z0, x1, y1, z1) -> {
			Rasterizer.setupVertex(V000, x0, y0, z0);
			Rasterizer.setupVertex(V001, x0, y0, z1);
			Rasterizer.setupVertex(V010, x0, y1, z0);
			Rasterizer.setupVertex(V011, x0, y1, z1);
			Rasterizer.setupVertex(V101, x1, y0, z1);
			Rasterizer.setupVertex(V111, x1, y1, z1);
			return testQuad(V101, V111, V011, V010) ||
					testQuad(V010, V000, V001, V101);
		};

		// NB: When three faces are visible, omit nearest vertex and draw two quads instead of three.

		BOX_TESTS[UP | EAST | NORTH] = (x0, y0, z0, x1, y1, z1) -> {
			Rasterizer.setupVertex(V000, x0, y0, z0);
			Rasterizer.setupVertex(V010, x0, y1, z0);
			Rasterizer.setupVertex(V011, x0, y1, z1);
			Rasterizer.setupVertex(V100, x1, y0, z0);
			Rasterizer.setupVertex(V101, x1, y0, z1);
			Rasterizer.setupVertex(V111, x1, y1, z1);
			return testQuad(V011, V111, V101, V100 ) ||
					testQuad(V100, V000, V010, V011);
		};

		BOX_TESTS[UP | WEST | NORTH] = (x0, y0, z0, x1, y1, z1) -> {
			Rasterizer.setupVertex(V000, x0, y0, z0);
			Rasterizer.setupVertex(V001, x0, y0, z1);
			Rasterizer.setupVertex(V011, x0, y1, z1);
			Rasterizer.setupVertex(V100, x1, y0, z0);
			Rasterizer.setupVertex(V110, x1, y1, z0);
			Rasterizer.setupVertex(V111, x1, y1, z1);
			return testQuad(V111, V110, V100, V000) ||
					testQuad(V000, V001, V011, V111);


		};

		BOX_TESTS[UP | EAST | SOUTH] = (x0, y0, z0, x1, y1, z1) -> {
			Rasterizer.setupVertex(V001, x0, y0, z1);
			Rasterizer.setupVertex(V010, x0, y1, z0);
			Rasterizer.setupVertex(V011, x0, y1, z1);
			Rasterizer.setupVertex(V100, x1, y0, z0);
			Rasterizer.setupVertex(V101, x1, y0, z1);
			Rasterizer.setupVertex(V110, x1, y1, z0);
			return testQuad(V010, V011, V001, V101) ||
					testQuad(V101, V100, V110, V010);
		};

		BOX_TESTS[UP | WEST | SOUTH] = (x0, y0, z0, x1, y1, z1) -> {
			Rasterizer.setupVertex(V000, x0, y0, z0);
			Rasterizer.setupVertex(V001, x0, y0, z1);
			Rasterizer.setupVertex(V010, x0, y1, z0);
			Rasterizer.setupVertex(V101, x1, y0, z1);
			Rasterizer.setupVertex(V110, x1, y1, z0);
			Rasterizer.setupVertex(V111, x1, y1, z1);
			return testQuad(V110, V010, V000, V001) ||
					testQuad(V001, V101, V111, V110);
		};

		BOX_TESTS[DOWN | EAST | NORTH] = (x0, y0, z0, x1, y1, z1) -> {
			Rasterizer.setupVertex(V000, x0, y0, z0);
			Rasterizer.setupVertex(V001, x0, y0, z1);
			Rasterizer.setupVertex(V010, x0, y1, z0);
			Rasterizer.setupVertex(V101, x1, y0, z1);
			Rasterizer.setupVertex(V110, x1, y1, z0);
			Rasterizer.setupVertex(V111, x1, y1, z1);
			return testQuad(V001, V000, V010, V110) ||
					testQuad(V110, V111, V101, V001);
		};

		BOX_TESTS[DOWN | WEST | NORTH] = (x0, y0, z0, x1, y1, z1) -> {
			Rasterizer.setupVertex(V001, x0, y0, z1);
			Rasterizer.setupVertex(V010, x0, y1, z0);
			Rasterizer.setupVertex(V011, x0, y1, z1);
			Rasterizer.setupVertex(V100, x1, y0, z0);
			Rasterizer.setupVertex(V101, x1, y0, z1);
			Rasterizer.setupVertex(V110, x1, y1, z0);
			return testQuad(V101, V001, V011, V010) ||
					testQuad(V010, V110, V100, V101);
		};

		BOX_TESTS[DOWN | EAST | SOUTH] = (x0, y0, z0, x1, y1, z1) -> {
			Rasterizer.setupVertex(V000, x0, y0, z0);
			Rasterizer.setupVertex(V001, x0, y0, z1);
			Rasterizer.setupVertex(V011, x0, y1, z1);
			Rasterizer.setupVertex(V100, x1, y0, z0);
			Rasterizer.setupVertex(V110, x1, y1, z0);
			Rasterizer.setupVertex(V111, x1, y1, z1);
			return testQuad(V000, V100, V110, V111) ||
					testQuad(V111, V011, V001, V000);
		};

		BOX_TESTS[DOWN | WEST | SOUTH] = (x0, y0, z0, x1, y1, z1) -> {
			Rasterizer.setupVertex(V000, x0, y0, z0);
			Rasterizer.setupVertex(V010, x0, y1, z0);
			Rasterizer.setupVertex(V011, x0, y1, z1);
			Rasterizer.setupVertex(V100, x1, y0, z0);
			Rasterizer.setupVertex(V101, x1, y0, z1);
			Rasterizer.setupVertex(V111, x1, y1, z1);
			return testQuad(V100, V101, V111, V011) ||
					testQuad(V011, V010, V000, V100);
		};

		////

		BOX_DRAWS[0] = (x0, y0, z0, x1, y1, z1) -> {
			// NOOP
		};

		BOX_DRAWS[UP] = (x0, y0, z0, x1, y1, z1) -> {
			Rasterizer.setupVertex(V010, x0, y1, z0);
			Rasterizer.setupVertex(V011, x0, y1, z1);
			Rasterizer.setupVertex(V110, x1, y1, z0);
			Rasterizer.setupVertex(V111, x1, y1, z1);
			drawQuad(V110, V010, V011, V111);
		};

		BOX_DRAWS[DOWN] = (x0, y0, z0, x1, y1, z1) -> {
			Rasterizer.setupVertex(V000, x0, y0, z0);
			Rasterizer.setupVertex(V001, x0, y0, z1);
			Rasterizer.setupVertex(V100, x1, y0, z0);
			Rasterizer.setupVertex(V101, x1, y0, z1);
			drawQuad(V000, V100, V101, V001);
		};

		BOX_DRAWS[EAST] = (x0, y0, z0, x1, y1, z1) -> {
			Rasterizer.setupVertex(V100, x1, y0, z0);
			Rasterizer.setupVertex(V101, x1, y0, z1);
			Rasterizer.setupVertex(V110, x1, y1, z0);
			Rasterizer.setupVertex(V111, x1, y1, z1);
			drawQuad(V101, V100, V110, V111);
		};

		BOX_DRAWS[WEST] = (x0, y0, z0, x1, y1, z1) -> {
			Rasterizer.setupVertex(V000, x0, y0, z0);
			Rasterizer.setupVertex(V001, x0, y0, z1);
			Rasterizer.setupVertex(V010, x0, y1, z0);
			Rasterizer.setupVertex(V011, x0, y1, z1);
			drawQuad(V000, V001, V011, V010);
		};

		BOX_DRAWS[NORTH] = (x0, y0, z0, x1, y1, z1) -> {
			Rasterizer.setupVertex(V000, x0, y0, z0);
			Rasterizer.setupVertex(V010, x0, y1, z0);
			Rasterizer.setupVertex(V100, x1, y0, z0);
			Rasterizer.setupVertex(V110, x1, y1, z0);
			drawQuad(V100, V000, V010, V110);
		};

		BOX_DRAWS[SOUTH] = (x0, y0, z0, x1, y1, z1) -> {
			Rasterizer.setupVertex(V001, x0, y0, z1);
			Rasterizer.setupVertex(V011, x0, y1, z1);
			Rasterizer.setupVertex(V101, x1, y0, z1);
			Rasterizer.setupVertex(V111, x1, y1, z1);
			drawQuad(V001, V101, V111, V011);
		};

		// NB: Split across two quads to give more evenly-sized test regions vs potentially one big and one very small
		BOX_DRAWS[UP | EAST] = (x0, y0, z0, x1, y1, z1) -> {
			Rasterizer.setupVertex(V010, x0, y1, z0);
			Rasterizer.setupVertex(V011, x0, y1, z1);
			Rasterizer.setupVertex(V100, x1, y0, z0);
			Rasterizer.setupVertex(V101, x1, y0, z1);
			Rasterizer.setupVertex(V110, x1, y1, z0);
			Rasterizer.setupVertex(V111, x1, y1, z1);
			drawQuad(V010, V011, V111, V101); drawQuad(V101, V100, V110, V010);
		};

		BOX_DRAWS[UP | WEST] = (x0, y0, z0, x1, y1, z1) -> {
			Rasterizer.setupVertex(V000, x0, y0, z0);
			Rasterizer.setupVertex(V001, x0, y0, z1);
			Rasterizer.setupVertex(V010, x0, y1, z0);
			Rasterizer.setupVertex(V011, x0, y1, z1);
			Rasterizer.setupVertex(V110, x1, y1, z0);
			Rasterizer.setupVertex(V111, x1, y1, z1);
			drawQuad(V111, V110, V010, V000);
			drawQuad(V000, V001, V011, V111);
		};

		BOX_DRAWS[UP | NORTH] = (x0, y0, z0, x1, y1, z1) -> {
			Rasterizer.setupVertex(V000, x0, y0, z0);
			Rasterizer.setupVertex(V010, x0, y1, z0);
			Rasterizer.setupVertex(V011, x0, y1, z1);
			Rasterizer.setupVertex(V100, x1, y0, z0);
			Rasterizer.setupVertex(V110, x1, y1, z0);
			Rasterizer.setupVertex(V111, x1, y1, z1);
			drawQuad(V011, V111, V110, V100);
			drawQuad(V100, V000, V010, V011);
		};

		BOX_DRAWS[UP | SOUTH] = (x0, y0, z0, x1, y1, z1) -> {
			Rasterizer.setupVertex(V001, x0, y0, z1);
			Rasterizer.setupVertex(V010, x0, y1, z0);
			Rasterizer.setupVertex(V011, x0, y1, z1);
			Rasterizer.setupVertex(V101, x1, y0, z1);
			Rasterizer.setupVertex(V110, x1, y1, z0);
			Rasterizer.setupVertex(V111, x1, y1, z1);
			drawQuad(V110, V010, V011, V001);
			drawQuad(V001, V101, V111, V110);
		};

		BOX_DRAWS[DOWN | EAST] = (x0, y0, z0, x1, y1, z1) -> {
			Rasterizer.setupVertex(V000, x0, y0, z0);
			Rasterizer.setupVertex(V001, x0, y0, z1);
			Rasterizer.setupVertex(V100, x1, y0, z0);
			Rasterizer.setupVertex(V101, x1, y0, z1);
			Rasterizer.setupVertex(V110, x1, y1, z0);
			Rasterizer.setupVertex(V111, x1, y1, z1);
			drawQuad(V001, V000, V100, V110);
			drawQuad(V110, V111, V101, V001);
		};

		BOX_DRAWS[DOWN | WEST] = (x0, y0, z0, x1, y1, z1) -> {
			Rasterizer.setupVertex(V000, x0, y0, z0);
			Rasterizer.setupVertex(V001, x0, y0, z1);
			Rasterizer.setupVertex(V010, x0, y1, z0);
			Rasterizer.setupVertex(V011, x0, y1, z1);
			Rasterizer.setupVertex(V100, x1, y0, z0);
			Rasterizer.setupVertex(V101, x1, y0, z1);
			drawQuad(V100, V101, V001, V011);
			drawQuad(V011, V010, V000, V100);
		};

		BOX_DRAWS[DOWN | NORTH] = (x0, y0, z0, x1, y1, z1) -> {
			Rasterizer.setupVertex(V000, x0, y0, z0);
			Rasterizer.setupVertex(V001, x0, y0, z1);
			Rasterizer.setupVertex(V010, x0, y1, z0);
			Rasterizer.setupVertex(V100, x1, y0, z0);
			Rasterizer.setupVertex(V101, x1, y0, z1);
			Rasterizer.setupVertex(V110, x1, y1, z0);
			drawQuad(V101, V001, V000, V010);
			drawQuad(V010, V110, V100, V101);
		};

		BOX_DRAWS[DOWN | SOUTH] = (x0, y0, z0, x1, y1, z1) -> {
			Rasterizer.setupVertex(V000, x0, y0, z0);
			Rasterizer.setupVertex(V001, x0, y0, z1);
			Rasterizer.setupVertex(V011, x0, y1, z1);
			Rasterizer.setupVertex(V100, x1, y0, z0);
			Rasterizer.setupVertex(V101, x1, y0, z1);
			Rasterizer.setupVertex(V111, x1, y1, z1);
			drawQuad(V000, V100, V101, V111);
			drawQuad(V111, V011, V001, V000);
		};

		BOX_DRAWS[NORTH | EAST] = (x0, y0, z0, x1, y1, z1) -> {
			Rasterizer.setupVertex(V000, x0, y0, z0);
			Rasterizer.setupVertex(V010, x0, y1, z0);
			Rasterizer.setupVertex(V100, x1, y0, z0);
			Rasterizer.setupVertex(V101, x1, y0, z1);
			Rasterizer.setupVertex(V110, x1, y1, z0);
			Rasterizer.setupVertex(V111, x1, y1, z1);
			drawQuad(V000, V010, V110, V111);
			drawQuad(V111, V101, V100, V000);
		};

		BOX_DRAWS[NORTH | WEST] = (x0, y0, z0, x1, y1, z1) -> {
			Rasterizer.setupVertex(V000, x0, y0, z0);
			Rasterizer.setupVertex(V001, x0, y0, z1);
			Rasterizer.setupVertex(V010, x0, y1, z0);
			Rasterizer.setupVertex(V011, x0, y1, z1);
			Rasterizer.setupVertex(V100, x1, y0, z0);
			Rasterizer.setupVertex(V110, x1, y1, z0);
			drawQuad(V110, V100, V000, V001);
			drawQuad(V001, V011, V010, V110);
		};

		BOX_DRAWS[SOUTH | EAST] = (x0, y0, z0, x1, y1, z1) -> {
			Rasterizer.setupVertex(V001, x0, y0, z1);
			Rasterizer.setupVertex(V011, x0, y1, z1);
			Rasterizer.setupVertex(V100, x1, y0, z0);
			Rasterizer.setupVertex(V101, x1, y0, z1);
			Rasterizer.setupVertex(V110, x1, y1, z0);
			Rasterizer.setupVertex(V111, x1, y1, z1);
			drawQuad(V011, V001, V101, V100);
			drawQuad(V100, V110, V111, V011);
		};

		BOX_DRAWS[SOUTH | WEST] = (x0, y0, z0, x1, y1, z1) -> {
			Rasterizer.setupVertex(V000, x0, y0, z0);
			Rasterizer.setupVertex(V001, x0, y0, z1);
			Rasterizer.setupVertex(V010, x0, y1, z0);
			Rasterizer.setupVertex(V011, x0, y1, z1);
			Rasterizer.setupVertex(V101, x1, y0, z1);
			Rasterizer.setupVertex(V111, x1, y1, z1);
			drawQuad(V101, V111, V011, V010);
			drawQuad(V010, V000, V001, V101);
		};

		// NB: When three faces are visible, omit nearest vertex and draw two quads instead of three.

		BOX_DRAWS[UP | EAST | NORTH] = (x0, y0, z0, x1, y1, z1) -> {
			Rasterizer.setupVertex(V000, x0, y0, z0);
			Rasterizer.setupVertex(V010, x0, y1, z0);
			Rasterizer.setupVertex(V011, x0, y1, z1);
			Rasterizer.setupVertex(V100, x1, y0, z0);
			Rasterizer.setupVertex(V101, x1, y0, z1);
			Rasterizer.setupVertex(V111, x1, y1, z1);
			drawQuad(V011, V111, V101, V100 );
			drawQuad(V100, V000, V010, V011);
		};

		BOX_DRAWS[UP | WEST | NORTH] = (x0, y0, z0, x1, y1, z1) -> {
			Rasterizer.setupVertex(V000, x0, y0, z0);
			Rasterizer.setupVertex(V001, x0, y0, z1);
			Rasterizer.setupVertex(V011, x0, y1, z1);
			Rasterizer.setupVertex(V100, x1, y0, z0);
			Rasterizer.setupVertex(V110, x1, y1, z0);
			Rasterizer.setupVertex(V111, x1, y1, z1);
			drawQuad(V111, V110, V100, V000);
			drawQuad(V000, V001, V011, V111);


		};

		BOX_DRAWS[UP | EAST | SOUTH] = (x0, y0, z0, x1, y1, z1) -> {
			Rasterizer.setupVertex(V001, x0, y0, z1);
			Rasterizer.setupVertex(V010, x0, y1, z0);
			Rasterizer.setupVertex(V011, x0, y1, z1);
			Rasterizer.setupVertex(V100, x1, y0, z0);
			Rasterizer.setupVertex(V101, x1, y0, z1);
			Rasterizer.setupVertex(V110, x1, y1, z0);
			drawQuad(V010, V011, V001, V101);
			drawQuad(V101, V100, V110, V010);
		};

		BOX_DRAWS[UP | WEST | SOUTH] = (x0, y0, z0, x1, y1, z1) -> {
			Rasterizer.setupVertex(V000, x0, y0, z0);
			Rasterizer.setupVertex(V001, x0, y0, z1);
			Rasterizer.setupVertex(V010, x0, y1, z0);
			Rasterizer.setupVertex(V101, x1, y0, z1);
			Rasterizer.setupVertex(V110, x1, y1, z0);
			Rasterizer.setupVertex(V111, x1, y1, z1);
			drawQuad(V110, V010, V000, V001);
			drawQuad(V001, V101, V111, V110);
		};

		BOX_DRAWS[DOWN | EAST | NORTH] = (x0, y0, z0, x1, y1, z1) -> {
			Rasterizer.setupVertex(V000, x0, y0, z0);
			Rasterizer.setupVertex(V001, x0, y0, z1);
			Rasterizer.setupVertex(V010, x0, y1, z0);
			Rasterizer.setupVertex(V101, x1, y0, z1);
			Rasterizer.setupVertex(V110, x1, y1, z0);
			Rasterizer.setupVertex(V111, x1, y1, z1);
			drawQuad(V001, V000, V010, V110);
			drawQuad(V110, V111, V101, V001);
		};

		BOX_DRAWS[DOWN | WEST | NORTH] = (x0, y0, z0, x1, y1, z1) -> {
			Rasterizer.setupVertex(V001, x0, y0, z1);
			Rasterizer.setupVertex(V010, x0, y1, z0);
			Rasterizer.setupVertex(V011, x0, y1, z1);
			Rasterizer.setupVertex(V100, x1, y0, z0);
			Rasterizer.setupVertex(V101, x1, y0, z1);
			Rasterizer.setupVertex(V110, x1, y1, z0);
			drawQuad(V101, V001, V011, V010);
			drawQuad(V010, V110, V100, V101);
		};

		BOX_DRAWS[DOWN | EAST | SOUTH] = (x0, y0, z0, x1, y1, z1) -> {
			Rasterizer.setupVertex(V000, x0, y0, z0);
			Rasterizer.setupVertex(V001, x0, y0, z1);
			Rasterizer.setupVertex(V011, x0, y1, z1);
			Rasterizer.setupVertex(V100, x1, y0, z0);
			Rasterizer.setupVertex(V110, x1, y1, z0);
			Rasterizer.setupVertex(V111, x1, y1, z1);
			drawQuad(V000, V100, V110, V111);
			drawQuad(V111, V011, V001, V000);
		};

		BOX_DRAWS[DOWN | WEST | SOUTH] = (x0, y0, z0, x1, y1, z1) -> {
			Rasterizer.setupVertex(V000, x0, y0, z0);
			Rasterizer.setupVertex(V010, x0, y1, z0);
			Rasterizer.setupVertex(V011, x0, y1, z1);
			Rasterizer.setupVertex(V100, x1, y0, z0);
			Rasterizer.setupVertex(V101, x1, y0, z1);
			Rasterizer.setupVertex(V111, x1, y1, z1);
			drawQuad(V100, V101, V111, V011);
			drawQuad(V011, V010, V000, V100);
		};
	}
}