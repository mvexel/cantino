#!/usr/bin/env python3
"""Call the Rust mobile ABI from a foreign runtime, including buffer ownership."""
import ctypes as c
import json
import pathlib
import tempfile
import threading
import sys

lib = c.CDLL(sys.argv[1])
ptr = c.c_void_p
string = c.c_char_p
lib.cantino_import.argtypes = [string,string,string,c.POINTER(ptr),c.POINTER(ptr)]
lib.cantino_open.argtypes = [string,c.POINTER(ptr),c.POINTER(ptr)]
lib.cantino_get.argtypes = [ptr,c.c_int32,c.c_int64,c.POINTER(ptr),c.POINTER(ptr)]
lib.cantino_query.argtypes = [ptr,string,c.POINTER(ptr),c.POINTER(ptr)]
lib.cantino_get_many.argtypes = [ptr,string,c.POINTER(ptr),c.POINTER(ptr)]
lib.cantino_way_coordinates.argtypes = [ptr,c.c_int64,c.POINTER(ptr),c.POINTER(ptr)]
lib.cantino_close.argtypes = [ptr,c.POINTER(ptr)]
lib.cantino_free.argtypes = [ptr]
lib.cantino_slice_job_request.argtypes = [string,string,string,c.POINTER(ptr),c.POINTER(ptr)]
lib.cantino_slice_job.argtypes = [string,string,c.POINTER(ptr),c.POINTER(ptr)]
lib.cantino_slice_progress.argtypes = [string,c.POINTER(ptr),c.POINTER(ptr)]
# CANTINO_ERROR_* (include/cantino.h): failures return the negative category.
NONE, INVALID_ARGUMENT, INVALID_FILE, IO, WRONG_THREAD, INTERNAL = range(6)

def call(function, *args):
    result, error = ptr(), ptr()
    code = function(*args,c.byref(result),c.byref(error))
    try:
        if code < 0:
            raise RuntimeError(code, c.string_at(error).decode() if error.value else 'native failure')
        return code, json.loads(c.string_at(result)) if result.value else None
    finally:
        lib.cantino_free(result)
        lib.cantino_free(error)

with tempfile.TemporaryDirectory() as directory:
    area = str(pathlib.Path(directory)/'area.sqlite').encode()
    fixture = pathlib.Path(__file__).resolve().parent.parent/'tests/fixtures/snapshot.osm'
    # Default options (NULL): untagged nodes keep no metadata.
    _, report = call(lib.cantino_import,str(fixture).encode(),area,None)
    assert report['counts'] == {'nodes':4,'ways':2,'relations':1}
    # Explicit supported options; unknown fields are rejected.
    try:
        call(lib.cantino_import,str(fixture).encode(),area,b'{"sort_pairs":3}')
        raise AssertionError('expected invalid option error')
    except RuntimeError as failure:
        assert failure.args[0] == -INVALID_ARGUMENT
    _, report = call(lib.cantino_import,str(fixture).encode(),area,b'{"preserve_untagged_metadata":true}')
    assert report['database_bytes'] > 0
    # Open failures: a missing file is IO, a file that is not an area (the
    # XML fixture) is INVALID_FILE.
    for path, expected in [(str(pathlib.Path(directory)/'missing.sqlite'), IO), (str(fixture), INVALID_FILE)]:
        handle, error = ptr(), ptr()
        assert lib.cantino_open(path.encode(),c.byref(handle),c.byref(error)) == -expected
        assert not handle.value
        lib.cantino_free(error)
    handle, error = ptr(), ptr()
    assert lib.cantino_open(area,c.byref(handle),c.byref(error)) == 0
    assert not error.value
    try:
        _, node = call(lib.cantino_get,handle,0,1)
        assert node['metadata']['user'] == 'Mapper'
        assert call(lib.cantino_get,handle,0,99) == (1,None)
        _, cafes = call(lib.cantino_query,handle,b'{"tags":[{"Equals":["amenity","cafe"]}]}')
        assert len(cafes) == 1 and cafes[0]['tags']['name'] == 'Café Test'
        try:
            call(lib.cantino_query,handle,b'{malformed')
            raise AssertionError('expected query error')
        except RuntimeError as failure:
            assert failure.args[0] == -INVALID_ARGUMENT
        # Another thread gets -WRONG_THREAD; the store is untouched.
        seen = []
        def foreign():
            out, err = ptr(), ptr()
            status = lib.cantino_get(handle,0,1,c.byref(out),c.byref(err))
            seen.append(status)
            lib.cantino_free(out)
            lib.cantino_free(err)
        worker = threading.Thread(target=foreign)
        worker.start()
        worker.join()
        assert seen == [-WRONG_THREAD], seen
        # A failed query leaves the store usable.
        assert call(lib.cantino_get,handle,1,1)[1]['nodes'] == [1,2,1]
        # NotExists: a post-check that needs a driver.
        _, unnamed = call(lib.cantino_query,handle,b'{"tags":[{"Exists":"highway"},{"NotExists":"name"}]}')
        assert [o['id'] for o in unnamed] == [1,2]
        try:
            call(lib.cantino_query,handle,b'{"tags":[{"NotExists":"name"}]}')
            raise AssertionError('expected no-driver error')
        except RuntimeError as failure:
            assert 'NotExists' in str(failure)
        # Batch get: input order, null for missing.
        _, batch = call(lib.cantino_get_many,handle,b'[{"type":"way","id":1},{"type":"node","id":99},{"type":"node","id":2}]')
        assert [o and o['id'] for o in batch] == [1,None,2]
        # Way coordinates: flat [lat_e7, lon_e7, ...], repeats kept.
        _, flat = call(lib.cantino_way_coordinates,handle,1)
        assert flat == [400000000,-1110000000,400010000,-1110010000,400000000,-1110000000]
        assert call(lib.cantino_way_coordinates,handle,42) == (1,None)
    finally:
        assert lib.cantino_close(handle,c.byref(error)) == 0
        lib.cantino_free(error)

# SliceOSM protocol helpers: NULL base means the public service.
_, request = call(lib.cantino_slice_job_request,None,
                  b'{"west":-111.9,"south":40.7,"east":-111.8,"north":40.8}','test'.encode())
assert request['url'] == 'https://slice.openstreetmap.us/api/'
assert json.loads(request['body'])['RegionData'] == [40.7,-111.9,40.8,-111.8]
_, job = call(lib.cantino_slice_job,b'http://127.0.0.1:9',b'2637da98-20a1-428f-b6db-18ac2861b763\n')
assert job['download_url'] == 'http://127.0.0.1:9/files/2637da98-20a1-428f-b6db-18ac2861b763.osm.pbf'
try:
    call(lib.cantino_slice_job,None,b'../../etc/passwd')
    raise AssertionError('expected job ID error')
except RuntimeError:
    pass
_, progress = call(lib.cantino_slice_progress,b'{"Complete":false,"ElemsTotal":4,"ElemsProg":1}')
assert progress == {'complete':False,'fraction':0.25,'size_bytes':None,'timestamp':None}

# Basemap extract: drive plan -> assembler over the committed PMTiles fixture,
# the way an adapter does (it plays the HTTP server by slicing the file).
u64 = c.c_uint64
lib.cantino_basemap_plan_new.argtypes = [string,c.c_int32,c.c_int32,c.c_double,c.POINTER(ptr),c.POINTER(ptr)]
lib.cantino_basemap_plan_first_request.argtypes = [ptr,c.POINTER(ptr),c.POINTER(ptr)]
lib.cantino_basemap_plan_feed.argtypes = [ptr,u64,c.c_char_p,c.c_size_t,c.POINTER(ptr),c.POINTER(ptr)]
lib.cantino_basemap_plan_outstanding.argtypes = [ptr,c.POINTER(ptr),c.POINTER(ptr)]
lib.cantino_basemap_plan_into_assembler.argtypes = [ptr,string,c.POINTER(ptr),c.POINTER(ptr)]
lib.cantino_basemap_plan_free.argtypes = [ptr,c.POINTER(ptr)]
lib.cantino_basemap_asm_write_range.argtypes = [ptr,u64,c.c_char_p,c.c_size_t,c.POINTER(ptr)]
lib.cantino_basemap_asm_write_range_file.argtypes = [ptr,u64,string,c.POINTER(ptr)]
lib.cantino_basemap_asm_remaining.argtypes = [ptr,c.POINTER(ptr),c.POINTER(ptr)]
lib.cantino_basemap_asm_progress.argtypes = [ptr,c.POINTER(ptr),c.POINTER(ptr)]
lib.cantino_basemap_asm_finish.argtypes = [ptr,string,c.POINTER(ptr)]
lib.cantino_basemap_asm_free.argtypes = [ptr,c.POINTER(ptr)]
lib.cantino_basemap_info.argtypes = [string,c.POINTER(ptr),c.POINTER(ptr)]

def status_call(function, *args):
    """For calls without a JSON result: raise on negative status, always free the error."""
    error = ptr()
    code = function(*args,c.byref(error))
    try:
        if code < 0:
            raise RuntimeError(code, c.string_at(error).decode() if error.value else 'native failure')
    finally:
        lib.cantino_free(error)

source = (pathlib.Path(__file__).resolve().parent.parent/'tests/fixtures/basemap/slc-nw-z12-15.pmtiles').read_bytes()
serve = lambda r: source[r['offset']:r['offset']+r['length']]
plan, error = ptr(), ptr()
assert lib.cantino_basemap_plan_new(b'{"west":-112.09,"south":40.83,"east":-112.06,"north":40.845}',
                                          -1,-1,0.05,c.byref(plan),c.byref(error)) == 0
lib.cantino_free(error)
_, first = call(lib.cantino_basemap_plan_first_request,plan)
assert first == {'id':0,'offset':0,'length':16384}
try:
    call(lib.cantino_basemap_plan_feed,plan,99,b'x',1)
    raise AssertionError('expected unknown-id error')
except RuntimeError:
    pass
queue, tiles = [first], None
while queue:
    r = queue.pop()
    body = serve(r)
    _, step = call(lib.cantino_basemap_plan_feed,plan,r['id'],body,len(body))
    if step == 'wait':
        continue
    if 'fetch' in step:
        queue += step['fetch']
    else:
        tiles = step['tiles_ready']
assert tiles and tiles['addressed_tiles'] == 22
assert call(lib.cantino_basemap_plan_outstanding,plan)[1] == []
with tempfile.TemporaryDirectory() as directory:
    d = pathlib.Path(directory)
    asm = ptr()
    assert lib.cantino_basemap_plan_into_assembler(plan,str(d/'out.part').encode(),c.byref(asm),c.byref(error)) == 0
    lib.cantino_free(error)
    _, remaining = call(lib.cantino_basemap_asm_remaining,asm)
    assert [r['id'] for r in remaining] == [r['id'] for r in tiles['requests']]
    for i, r in enumerate(remaining):
        if i % 2:
            (d/'range').write_bytes(serve(r))
            status_call(lib.cantino_basemap_asm_write_range_file,asm,r['id'],str(d/'range').encode())
        else:
            status_call(lib.cantino_basemap_asm_write_range,asm,r['id'],serve(r),r['length'])
    _, progress = call(lib.cantino_basemap_asm_progress,asm)
    assert progress['ranges_done'] == progress['ranges_total'] == len(remaining)
    status_call(lib.cantino_basemap_asm_finish,asm,str(d/'out.pmtiles').encode())
    status_call(lib.cantino_basemap_asm_free,asm)
    assert not (d/'out.part').exists()
    _, info = call(lib.cantino_basemap_info,str(d/'out.pmtiles').encode())
    assert info['addressed_tiles'] == 22 and info['spec_version'] == 3
    assert info['file_bytes'] == (d/'out.pmtiles').stat().st_size == tiles['archive_bytes']
status_call(lib.cantino_basemap_plan_free,None)
print('Rust mobile C ABI import/open/get/get_many/query/way_coordinates/error/free/slice/basemap checks passed')
