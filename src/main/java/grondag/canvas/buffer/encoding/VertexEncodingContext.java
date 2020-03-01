/*******************************************************************************
 * Copyright 2019, 2020 grondag
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.  You may obtain a copy
 * of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations under
 * the License.
 ******************************************************************************/


package grondag.canvas.buffer.encoding;

import java.util.function.Function;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.Matrix3f;
import net.minecraft.client.util.math.Matrix4f;
import net.minecraft.client.util.math.Vector3f;

import grondag.canvas.apiimpl.mesh.MutableQuadViewImpl;
import grondag.canvas.buffer.packing.VertexCollectorList;
import grondag.canvas.material.MaterialContext;

/**
 * Base quad-rendering class for fallback and mesh consumers.
 * Has most of the actual buffer-time lighting and coloring logic.
 */
public abstract class VertexEncodingContext  {
	public static final int FULL_BRIGHTNESS = 0xF000F0;

	@Deprecated
	protected final Function<RenderLayer, VertexConsumer> bufferFunc; // TODO: remove when other contexts are done
	protected final VertexCollectorList collectors;
	protected final Vector3f normalVec = new Vector3f();

	protected VertexEncodingContext(Function<RenderLayer, VertexConsumer> bufferFunc, VertexCollectorList collectors) {
		this.bufferFunc = bufferFunc;
		this.collectors = collectors;
	}

	public abstract MaterialContext materialContext();

	public abstract Matrix4f matrix();

	public abstract Matrix3f normalMatrix();

	public abstract int overlay();

	public Vector3f normalVec() {
		return normalVec;
	}

	public abstract VertexConsumer consumer(MutableQuadViewImpl quad);

	public abstract int indexedColor(int colorIndex);

	public abstract void applyLighting(MutableQuadViewImpl quad);

	public abstract void computeLighting(MutableQuadViewImpl quad);
}
