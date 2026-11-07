import hashlib
import importlib.util
from pathlib import Path
import struct
import unittest
import zlib
spec=importlib.util.spec_from_file_location('loader_isolation',Path(__file__).with_name('prepare-lspatch-kotlin-loader.py'))
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
def fixture(strings):
    data=bytearray(112+4*len(strings));data[:8]=b'dex\n035\x00';struct.pack_into('<I',data,36,112);struct.pack_into('<I',data,40,0x12345678);struct.pack_into('<II',data,56,len(strings),112)
    for i,s in enumerate(strings):
        raw=s.encode('utf-8');assert len(s)<128
        struct.pack_into('<I',data,112+4*i,len(data));data+=bytes([len(s)])+raw+b'\x00'
    struct.pack_into('<I',data,32,len(data));data[12:32]=hashlib.sha1(data[32:]).digest();struct.pack_into('<I',data,8,zlib.adler32(data[12:])&0xffffffff);return bytes(data)
class LoaderIsolationTest(unittest.TestCase):
    def testRelocationPreservesOffsetsSortAndDigests(self):
        old=fixture(['Lkotlin/jvm/internal/Intrinsics;','Lorg/matrix/vector/Entry;','kotlin.jvm.internal.Intrinsics'])
        fixed,r=m.relocate(old);self.assertEqual(len(old),len(fixed));self.assertNotIn(b'kotlin',fixed);self.assertIn(b'kotlim',fixed);self.assertEqual(2,r['modifiedStrings'])
        self.assertEqual(hashlib.sha1(fixed[32:]).digest(),fixed[12:32]);self.assertEqual(zlib.adler32(fixed[12:])&0xffffffff,struct.unpack_from('<I',fixed,8)[0])
        self.assertEqual(old[32:112+12],fixed[32:112+12])
    def testCorruptOriginalSignatureIsRejected(self):
        data=bytearray(fixture(['Lkotlin/jvm/internal/Intrinsics;']));data[-2]^=1
        with self.assertRaisesRegex(ValueError,'SHA1'):m.relocate(bytes(data))
    def testExistingNamespaceIsRejected(self):
        data=fixture(['Lkotlim/Existing;','Lkotlin/jvm/internal/Intrinsics;'])
        with self.assertRaisesRegex(ValueError,'namespace already'):m.relocate(data)
    def testMovingAcrossAnExistingSortNeighborIsRejected(self):
        data=fixture(['Lkotlimz/Neighbor;','Lkotlin/jvm/internal/Intrinsics;'])
        with self.assertRaisesRegex(ValueError,'string order'):m.relocate(data)
    def testMissingBundledRuntimeIsRejected(self):
        with self.assertRaisesRegex(ValueError,'No bundled'):m.relocate(fixture(['Lorg/Example;']))
    def testMalformedHeaderIsRejected(self):
        with self.assertRaisesRegex(ValueError,'Not a standard'):m.relocate(b'not-dex')
    def testUnsortedInputIsRejected(self):
        with self.assertRaisesRegex(ValueError,'Original strings'):m.relocate(fixture(['Lorg/Example;','Lkotlin/jvm/internal/Intrinsics;']))
    def testOversizedUlebIsRejected(self):
        with self.assertRaisesRegex(ValueError,'Oversized'):m.uleb(b'\x80'*5,0)
if __name__=='__main__':unittest.main()
