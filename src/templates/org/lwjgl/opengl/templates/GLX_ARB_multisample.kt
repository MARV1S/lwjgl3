/* 
 * Copyright LWJGL. All rights reserved.
 * License terms: http://lwjgl.org/license.php
 */
package org.lwjgl.opengl.templates

import org.lwjgl.generator.*
import org.lwjgl.opengl.*

val GLX_ARB_multisample = "GLXARBMultisample".nativeClassGLX("GLX_ARB_multisample", ARB) {
	javaImport(
		"org.lwjgl.system.linux.*",
		"org.lwjgl.system.linux.GLX"
	)

	documentation =
		"""
		Native bindings to the ${registryLink("ARB", "multisample")} extension.

		This extension provides a mechanism to antialias all GL primitives.
		"""

	IntConstant.block(
		"Accepted by the {@code attribList} parameter of GLX#ChooseVisual(), and by the {@code attrib} parameter of GLX#GetConfig().",

		"SAMPLE_BUFFERS_ARB" _ 100000,
		"SAMPLES_ARB" _ 100001
	)

}