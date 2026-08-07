let runtime;
let activeGenerator;

self.addEventListener('message', event => {
  const request = event.data;
  predict(request).catch(error => {
    self.postMessage({id: request.id, error: error.message});
  });
});

async function predict(request) {
  const instance = await loadRuntime();
  const generator = `${request.version}:${request.seed}:${request.dimension}:${request.flags}`;
  if (generator !== activeGenerator) {
    const result = instance.exports.cfxWebInit(
      request.version,
      BigInt(request.seed),
      request.dimension,
      request.flags
    );
    if (result !== 0) throw new Error(`predictor initialization failed: ${result}`);
    activeGenerator = generator;
  }
  const result = instance.exports.cfxWebGenerateTile(
    request.blockX,
    request.blockZ,
    request.lod,
    request.exact ? 1 : 0
  );
  if (result !== 0) throw new Error(`terrain prediction failed: ${result}`);
  const cells = 258 * 258;
  const biomes = copyInt32(instance, instance.exports.cfxWebBiomeData(), cells);
  const heights = copyInt32(instance, instance.exports.cfxWebHeightData(), cells);
  const surfaces = copyInt32(instance, instance.exports.cfxWebSurfaceData(), cells);
  const canopies = copyInt32(instance, instance.exports.cfxWebCanopyData(), cells);
  const subCells = request.lod >= 3 && request.dimension !== 1 ? cells * 4 : 0;
  const subBiomes = copyInt32(instance, instance.exports.cfxWebSubBiomeData(), subCells);
  const subSurfaces = copyInt32(instance, instance.exports.cfxWebSubSurfaceData(), subCells);
  const subCanopies = copyInt32(instance, instance.exports.cfxWebSubCanopyData(), subCells);
  self.postMessage({
    id: request.id,
    biomes: biomes.buffer,
    heights: heights.buffer,
    surfaces: surfaces.buffer,
    canopies: canopies.buffer,
    subBiomes: subBiomes.buffer,
    subSurfaces: subSurfaces.buffer,
    subCanopies: subCanopies.buffer
  }, [biomes.buffer, heights.buffer, surfaces.buffer, canopies.buffer,
    subBiomes.buffer, subSurfaces.buffer, subCanopies.buffer]);
}

function copyInt32(instance, address, length) {
  const copy = new Int32Array(length);
  copy.set(new Int32Array(instance.exports.memory.buffer, address, length));
  return copy;
}

function loadRuntime() {
  if (!runtime) {
    const success = () => 0;
    const imports = {
      wasi_snapshot_preview1: {
        args_get: success,
        args_sizes_get: success,
        fd_close: success,
        fd_fdstat_get: success,
        fd_seek: success,
        fd_write: success,
        proc_exit: code => { throw new Error(`predictor exited: ${code}`); }
      }
    };
    runtime = WebAssembly.instantiateStreaming(fetch('/predictor.wasm'), imports)
      .then(result => result.instance);
  }
  return runtime;
}
