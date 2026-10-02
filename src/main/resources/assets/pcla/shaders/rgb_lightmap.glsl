uniform sampler2D pcla_RawLightmap;
uniform float pcla_Gamma;
uniform float pcla_NightVision;

vec3 pcla_decode(float lane) {
    lane=mod(lane+65536.0,32768.0);
    return vec3(floor(lane/1024.0),mod(floor(lane/32.0),32.0),mod(lane,32.0))*0.5;
}
bool pcla_encoded(vec2 light) {
    return all(greaterThanEqual(mod(light+65536.0,65536.0),vec2(32768.0)));
}
vec3 pcla_light(vec2 coordinates) {
    vec3 block=pcla_decode(coordinates.x),sky=pcla_decode(coordinates.y);
    vec3 color=vec3(
        texture2D(pcla_RawLightmap,(vec2(block.r,sky.r)+0.5)/16.0).r,
        texture2D(pcla_RawLightmap,(vec2(block.g,sky.g)+0.5)/16.0).g,
        texture2D(pcla_RawLightmap,(vec2(block.b,sky.b)+0.5)/16.0).b);
    float peak=max(color.r,max(color.g,color.b));
    if(peak>0.0)color=mix(color,color/peak,pcla_NightVision);
    color=clamp(color,0.0,1.0);
    vec3 inv=1.0-color;
    color=mix(color,1.0-inv*inv*inv*inv,pcla_Gamma);
    return clamp(color*0.96+0.03,0.0,1.0);
}
