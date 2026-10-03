#!/usr/bin/env python3
"""Call the Rust mobile ABI from a foreign runtime, including buffer ownership."""
import ctypes as c
import json
import pathlib
import tempfile
import sys

lib = c.CDLL(sys.argv[1])
ptr = c.c_void_p
string = c.c_char_p
lib.osm_framework_import.argtypes = [string,string,string,c.POINTER(ptr),c.POINTER(ptr)]
lib.osm_framework_open.argtypes = [string,c.POINTER(ptr),c.POINTER(ptr)]
lib.osm_framework_get.argtypes = [ptr,c.c_int32,c.c_int64,c.POINTER(ptr),c.POINTER(ptr)]
lib.osm_framework_query.argtypes = [ptr,string,c.POINTER(ptr),c.POINTER(ptr)]
lib.osm_framework_close.argtypes = [ptr,c.POINTER(ptr)]
lib.osm_framework_free.argtypes = [ptr]

def call(function, *args):
    result, error = ptr(), ptr()
    code = function(*args,c.byref(result),c.byref(error))
    try:
        if code == -1:
            raise RuntimeError(c.string_at(error).decode() if error.value else 'native failure')
        return code, json.loads(c.string_at(result)) if result.value else None
    finally:
        lib.osm_framework_free(result)
        lib.osm_framework_free(error)

with tempfile.TemporaryDirectory() as directory:
    area = str(pathlib.Path(directory)/'area.osmx').encode()
    fixture = pathlib.Path(__file__).resolve().parent.parent/'tests/fixtures/snapshot.osm'
    _, report = call(lib.osm_framework_import,str(fixture).encode(),area,None)
    assert report['counts'] == {'nodes':4,'ways':2,'relations':1}
    handle, error = ptr(), ptr()
    assert lib.osm_framework_open(area,c.byref(handle),c.byref(error)) == 0
    assert not error.value
    try:
        _, node = call(lib.osm_framework_get,handle,0,1)
        assert node['metadata']['user'] == 'Mapper'
        assert call(lib.osm_framework_get,handle,0,99) == (1,None)
        _, cafes = call(lib.osm_framework_query,handle,b'{"tags":[{"Equals":["amenity","cafe"]}]}')
        assert len(cafes) == 1 and cafes[0]['tags']['name'] == 'Café Test'
        try:
            call(lib.osm_framework_query,handle,b'{malformed')
            raise AssertionError('expected query error')
        except RuntimeError:
            pass
        # A failed query leaves the store usable.
        assert call(lib.osm_framework_get,handle,1,1)[1]['nodes'] == [1,2,1]
    finally:
        assert lib.osm_framework_close(handle,c.byref(error)) == 0
        lib.osm_framework_free(error)
print('Rust mobile C ABI import/open/get/query/error/free checks passed')
