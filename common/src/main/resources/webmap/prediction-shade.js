export function shadePrediction(argb, tint) {
  const factor = (tint & 255) / 255;
  const scale = (c) => Math.trunc(Math.fround(c * Math.fround(factor)));
  return (
    ((argb & 0xff000000) |
      (scale((argb >>> 16) & 255) << 16) |
      (scale((argb >>> 8) & 255) << 8) |
      scale(argb & 255)) >>>
    0
  );
}
export function authoritativePixel(patch, pixel) {
  return !!(patch?.evaluated?.[pixel >>> 3] & (1 << (pixel & 7)));
}
