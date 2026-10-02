#version 120
// PCLA_LIGHTMAP_FUNCTIONS
uniform bool pcla_Lighting;
uniform bool pcla_Light0;
uniform bool pcla_Light1;
uniform bool pcla_RadialFog;
uniform bool pcla_EntityOverride;
uniform vec2 pcla_EntityLight;
uniform float pcla_EntityFloor;

vec4 pcla_vertex_color(vec4 eye) {
    if(!pcla_Lighting)return gl_Color;
    vec3 n=normalize(gl_NormalMatrix*gl_Normal);
    vec3 diffuse=gl_LightModel.ambient.rgb;
    if(pcla_Light0)diffuse+=gl_LightSource[0].ambient.rgb+gl_LightSource[0].diffuse.rgb*
        max(0.0,dot(n,normalize(gl_LightSource[0].position.xyz-eye.xyz*gl_LightSource[0].position.w)));
    if(pcla_Light1)diffuse+=gl_LightSource[1].ambient.rgb+gl_LightSource[1].diffuse.rgb*
        max(0.0,dot(n,normalize(gl_LightSource[1].position.xyz-eye.xyz*gl_LightSource[1].position.w)));
    return vec4(gl_Color.rgb*clamp(diffuse,0.0,1.0),gl_Color.a);
}
void main() {
    vec4 eye=gl_ModelViewMatrix*gl_Vertex;
    gl_Position=ftransform();
    vec4 color=pcla_vertex_color(eye);
    gl_TexCoord[0]=gl_TextureMatrix[0]*gl_MultiTexCoord0;
    gl_TexCoord[2]=gl_TextureMatrix[2]*gl_MultiTexCoord2;
    vec2 lightCoordinates=gl_MultiTexCoord1.xy;
    if(pcla_EntityOverride && !pcla_encoded(lightCoordinates)
            && !all(greaterThanEqual(lightCoordinates,vec2(240.0)))
            && lightCoordinates.x<=pcla_EntityFloor+16.0)
        lightCoordinates=pcla_EntityLight;
    if(pcla_encoded(lightCoordinates)) {
        vec3 light=pcla_light(lightCoordinates);
        // Slices 1..17 form an identity RGB cube. Slice 0 preserves the native 2D lightmap.
        gl_TexCoord[1]=vec4((light.rg*15.0+0.5)/16.0,(light.b*16.0+1.5)/18.0,1.0);
    } else {
        vec4 uv=gl_TextureMatrix[1]*gl_MultiTexCoord1;
        uv.xy=clamp(uv.xy,vec2(0.5/16.0),vec2(15.5/16.0));
        uv.z=0.5/18.0;
        gl_TexCoord[1]=uv;
    }
    gl_FrontColor=color;
    gl_BackColor=color;
    gl_FrontSecondaryColor=vec4(0.0);
    gl_BackSecondaryColor=vec4(0.0);
    gl_FogFragCoord=pcla_RadialFog?length(eye.xyz):abs(eye.z);
}
