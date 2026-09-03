package com.example.yucam.gl

/**
 * Y2K Warm LUT / Grain / Chromatic Aberration / Vignette shader set.
 */
object Shaders {
    const val VERTEX_SHADER = """
        attribute vec4 position;
        attribute vec4 inputTextureCoordinate;
        varying vec2 textureCoordinate;
        const mat4 identity = mat4(
            1.0, 0.0, 0.0, 0.0,
            0.0, 1.0, 0.0, 0.0,
            0.0, 0.0, 1.0, 0.0,
            0.0, 0.0, 0.0, 1.0
        );
        void main() {
            gl_Position = position;
            textureCoordinate = inputTextureCoordinate.xy;
        }
    """

    const val Y2K_FRAGMENT_SHADER = """
        precision mediump float;
        varying vec2 textureCoordinate;
        uniform samplerExternalOES inputImageTexture;
        uniform float time;
        
        // Pseudo-random noise function
        float rand(vec2 co){
            return fract(sin(dot(co.xy ,vec2(12.9898,78.233))) * 43758.5453);
        }

        void main() {
            vec4 textureColor = texture2D(inputImageTexture, textureCoordinate);
            
            // Add slight warmth/tint (simplified Y2K look)
            textureColor.r = min(textureColor.r * 1.1, 1.0);
            textureColor.b *= 0.9;
            
            // Add subtle grain/noise
            float noise = (rand(textureCoordinate * time) - 0.5) * 0.08;
            textureColor.rgb += noise;
            
            // Vignette (simplified)
            float dist = distance(textureCoordinate, vec2(0.5, 0.5));
            textureColor.rgb *= smoothstep(0.85, 0.2, dist);
            
            gl_FragColor = textureColor;
        }
    """
}