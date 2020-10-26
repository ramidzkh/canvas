/*
 * Copyright 2019, 2020 grondag
 *
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
 */

package grondag.canvas.material.state;

import grondag.canvas.apiimpl.MaterialConditionImpl;
import grondag.canvas.material.property.MaterialDecal;
import grondag.canvas.material.property.MaterialDepthTest;
import grondag.canvas.material.property.MaterialFog;
import grondag.canvas.material.property.MaterialTarget;
import grondag.canvas.material.property.MaterialTextureState;
import grondag.canvas.material.property.MaterialTransparency;
import grondag.canvas.material.property.MaterialWriteMask;
import grondag.canvas.shader.MaterialShaderImpl;
import grondag.canvas.shader.MaterialShaderManager;

abstract class AbstractRenderState extends AbstractRenderStateView {
	public final int index;

	/**
	 * OpenGL primitive constant. Determines number of vertices.
	 *
	 * Currently used in vanilla are...
	 * GL_LINES
	 * GL_LINE_STRIP (currently GUI only)
	 * GL_TRIANGLE_STRIP (currently GUI only)
	 * GL_TRIANGLE_FAN (currently GUI only)
	 * GL_QUADS
	 */
	public final int primitive;

	public final MaterialTextureState texture;
	public final boolean bilinear;
	public final MaterialTransparency transparency;
	public final MaterialDepthTest depthTest;
	public final boolean cull;
	public final MaterialWriteMask writeMask;
	public final boolean enableLightmap;
	public final MaterialDecal decal;
	public final boolean sorted;
	public final MaterialTarget target;
	public final boolean lines;
	public final MaterialFog fog;
	public final MaterialShaderImpl shader;
	public final MaterialConditionImpl condition;

	/**
	 * True when translucent transparency and targets the terrain layer.
	 * Should not be rendered until that framebuffer is initialized in fabulous mode
	 * or should be delayed to render with other trasnslucent when not.
	 */
	public final boolean isTranslucentTerrain;

	protected AbstractRenderState(int index, long bits) {
		super(bits);
		this.index = index;
		primitive = primitive();
		texture = texture();
		bilinear = bilinear();
		depthTest = depthTest();
		cull = cull();
		writeMask = writeMask();
		enableLightmap = enableLightmap();
		decal = decal();
		target = target();
		lines = lines();
		fog = fog();
		condition = condition();
		transparency = TRANSPARENCY.getValue(bits);
		sorted = transparency != MaterialTransparency.NONE && decal != MaterialDecal.TRANSLUCENT;
		shader = MaterialShaderManager.INSTANCE.get(SHADER.getValue(bits));
		isTranslucentTerrain = (target == MaterialTarget.MAIN || target == MaterialTarget.TRANSLUCENT) && transparency == MaterialTransparency.TRANSLUCENT;
	}
}
