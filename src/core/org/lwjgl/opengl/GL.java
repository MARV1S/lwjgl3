/*
 * Copyright LWJGL. All rights reserved.
 * License terms: http://lwjgl.org/license.php
 */
package org.lwjgl.opengl;

import org.lwjgl.LWJGLUtil;
import org.lwjgl.system.APIBuffer;
import org.lwjgl.system.APIUtil;
import org.lwjgl.system.FunctionMap;
import org.lwjgl.system.FunctionProvider;
import org.lwjgl.system.windows.WindowsLibrary;

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;
import java.util.StringTokenizer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL32.*;
import static org.lwjgl.opengl.WGLARBExtensionsString.*;
import static org.lwjgl.opengl.WGLEXTExtensionsString.*;
import static org.lwjgl.system.Checks.*;
import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.system.windows.WGL.*;
import static org.lwjgl.system.windows.WinBase.*;

public class GL {

	private static final FunctionProvider functionProvider;

	static {
		switch ( LWJGLUtil.getPlatform() ) {
			case LWJGLUtil.PLATFORM_WINDOWS:
				functionProvider = new FunctionProvider() {

					private final WindowsLibrary OPENGL = new WindowsLibrary("opengl32.dll");

					@Override
					public long getFunctionAddress(final String functionName) {
						final ByteBuffer nameBuffer = memEncodeASCII(functionName);
						long address = wglGetProcAddress(nameBuffer);
						if ( address == 0L ) {
							address = GetProcAddress(OPENGL.getHandle(), nameBuffer);
							if ( address == 0L )
								LWJGLUtil.log("Failed to locate address for GL function " + functionName);
						}

						return address;
					}
				};
				break;
			case LWJGLUtil.PLATFORM_LINUX:
			case LWJGLUtil.PLATFORM_MACOSX:
			default:
				throw new UnsupportedOperationException();
		}
	}

	private static final ThreadLocal<GLContext> contextTL = new ThreadLocal<GLContext>();

	public GL() {
	}

	public static FunctionProvider getFunctionProvider() {
		return functionProvider;
	}

	static void setCurrent(final GLContext context) {
		contextTL.set(context);
	}

	public static ContextCapabilities getCapabilities() {
		return contextTL.get().getCapabilities();
	}

	/**
	 * Creates a new ContextCapabilities instance for the current OpenGL context.
	 * <p/>
	 * Depending on the current context, the instance returned may or may not contain the
	 * deprecated functionality removed since OpenGL version 3.1. The {@code forwardCompatible}
	 * flag will force LWJGL to not load the deprecated functions, even if the current context
	 * exposes them.
	 *
	 * @param forwardCompatible if true, LWJGL will create forward compatible capabilities
	 *
	 * @return the ContextCapabilities instance
	 */
	public static ContextCapabilities createCapabilities(boolean forwardCompatible) {
		// We don't have a current ContextCapabilities when this method is called
		// so we have to use the native bindings directly.
		final long GetError = functionProvider.getFunctionAddress("glGetError");
		final long GetString = functionProvider.getFunctionAddress("glGetString");
		final long GetIntegerv = functionProvider.getFunctionAddress("glGetIntegerv");

		if ( GetError == 0L || GetString == 0L || GetIntegerv == 0L )
			throw new IllegalStateException("Core OpenGL functions could not be found. Make sure that a GL context is current in the current thread.");

		int errorCode = nglGetError(GetError);
		if ( errorCode != GL_NO_ERROR )
			LWJGLUtil.log("A GL context was in an error state before the creation of its capabilities instance. Error: " + Util.translateGLErrorString(errorCode));

		final APIBuffer __buffer = APIUtil.apiBuffer();

		final int majorVersion;
		final int minorVersion;

		// Try the 3.0+ version query first
		nglGetIntegerv(GL_MAJOR_VERSION, __buffer.address(), GetIntegerv);
		errorCode = nglGetError(GetError);
		if ( errorCode == GL_NO_ERROR ) {
			// We're on an 3.0+ context.
			majorVersion = __buffer.intValue(0);
			assert 3 <= majorVersion;

			nglGetIntegerv(GL_MINOR_VERSION, __buffer.address(), GetIntegerv);
			minorVersion = __buffer.intValue(0);
		} else {
			// Fallback to the string query.
			final String version = memDecodeUTF8(memByteBufferNT1(checkPointer(nglGetString(GL_VERSION, GetString))));

			try {
				final StringTokenizer versionTokenizer = new StringTokenizer(version, ". ");

				majorVersion = Integer.parseInt(versionTokenizer.nextToken());
				minorVersion = Integer.parseInt(versionTokenizer.nextToken());
			} catch (Exception e) {
				throw new IllegalStateException("The OpenGL version string is malformed: " + version, e);
			}
		}

		final int[][] GL_VERSIONS = {
			{ 1, 2, 3, 4, 5 },  // OpenGL 1
			{ 0, 1 },           // OpenGL 2
			{ 0, 1, 2, 3 },     // OpenGL 3
			{ 0, 1, 2 },        // OpenGL 4
		};

		final Set<String> supportedExtensions = new HashSet<String>(128);

		for ( int major = 1; major <= GL_VERSIONS.length; major++ ) {
			int[] minors = GL_VERSIONS[major - 1];
			for ( int minor : minors ) {
				if ( major < majorVersion || (major == majorVersion && minor <= minorVersion) )
					supportedExtensions.add("OpenGL" + Integer.toString(major) + Integer.toString(minor));
			}
		}

		if ( majorVersion < 3 ) {
			// Parse EXTENSIONS string
			final String extensionsString = memDecodeASCII(memByteBufferNT1(checkPointer(nglGetString(GL_EXTENSIONS, GetString))));

			final StringTokenizer tokenizer = new StringTokenizer(extensionsString);
			while ( tokenizer.hasMoreTokens() )
				supportedExtensions.add(tokenizer.nextToken());
		} else {
			// Use forward compatible indexed EXTENSIONS

			nglGetIntegerv(GL_NUM_EXTENSIONS, __buffer.address(), GetIntegerv);
			final int extensionCount = __buffer.intValue(0);

			final long GetStringi = checkPointer(checkFunctionAddress(functionProvider.getFunctionAddress("glGetStringi")));
			for ( int i = 0; i < extensionCount; i++ )
				supportedExtensions.add(memDecodeASCII(memByteBufferNT1(nglGetStringi(GL_EXTENSIONS, i, GetStringi))));

			// In real drivers, we may encounter the following weird scenarios:
			// - 3.1 context without GL_ARB_compatibility but with deprecated functionality exposed and working.
			// - Core or forward-compatible context with GL_ARB_compatibility exposed, but not working when used.
			// We ignore these and go by the spec.

			// Force forwardCompatible to true if the context is a forward-compatible context.
			nglGetIntegerv(GL_CONTEXT_FLAGS, __buffer.address(), GetIntegerv);
			if ( (__buffer.intValue(0) & GL_CONTEXT_FLAG_FORWARD_COMPATIBLE_BIT) != 0 )
				forwardCompatible = true;
			else {
				// Force forwardCompatible to true if the context is a core profile context.
				if ( (3 < majorVersion || 1 <= minorVersion) ) { // OpenGL 3.1+
					if ( 3 < majorVersion || 2 <= minorVersion ) { // OpenGL 3.2+
						nglGetIntegerv(GL_CONTEXT_PROFILE_MASK, __buffer.address(), GetIntegerv);
						if ( (__buffer.intValue(0) & GL_CONTEXT_CORE_PROFILE_BIT) != 0 )
							forwardCompatible = true;
					} else
						forwardCompatible = !supportedExtensions.contains("GL_ARB_compatibility");
				}
			}
		}

		switch ( LWJGLUtil.getPlatform() ) {
			case LWJGLUtil.PLATFORM_WINDOWS:
				addWGLExtensions(supportedExtensions);
				break;
			case LWJGLUtil.PLATFORM_LINUX:
			case LWJGLUtil.PLATFORM_MACOSX:
			default:
				throw new UnsupportedOperationException();
		}

		return new ContextCapabilities(supportedExtensions, forwardCompatible);
	}

	private static void addWGLExtensions(final Set<String> supportedExtensions) {
		final String wglExtensions;

		long wglGetExtensionsString = functionProvider.getFunctionAddress("wglGetExtensionsStringARB");
		if ( wglGetExtensionsString != 0L ) {
			wglExtensions = memDecodeASCII(memByteBufferNT1(nwglGetExtensionsStringARB(wglGetCurrentDC(), wglGetExtensionsString)));
		} else {
			wglGetExtensionsString = functionProvider.getFunctionAddress("wglGetExtensionsStringEXT");
			if ( wglGetExtensionsString == 0L )
				return;

			wglExtensions = memDecodeASCII(memByteBufferNT1(nwglGetExtensionsStringEXT(wglGetExtensionsString)));
		}

		final StringTokenizer tokenizer = new StringTokenizer(wglExtensions);
		while ( tokenizer.hasMoreTokens() )
			supportedExtensions.add(tokenizer.nextToken());
	}

	static <T extends FunctionMap> T checkExtension(final String extension, final T functions, final boolean supported) {
		if ( supported )
			return functions;
		else {
			LWJGLUtil.log("[GL] " + extension + " was reported as available but an entry point is missing.");
			return null;
		}
	}

}