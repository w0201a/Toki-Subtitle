import importlib.util
import io
import json
from pathlib import Path
import unittest
import zipfile

spec=importlib.util.spec_from_file_location('lspatch_triage',Path(__file__).with_name('analyze-lspatch-report.py'))
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)

class ReportTriageTest(unittest.TestCase):
    def report(self,entries):
        source=io.BytesIO()
        with zipfile.ZipFile(source,'w')as z:
            for name,value in entries.items():z.writestr(name,value)
        source.seek(0)
        return module.analyze(source)
    def stack(self):
        return '11-07 22:34:59.341 E VectorNative: java.lang.NoSuchMethodError: '+module.COLLISION+' in class Lkotlin/jvm/internal/Intrinsics;\n at org.matrix.vector.impl.core.VectorInlinedCallers.<clinit>(VectorInlinedCallers.kt:63)\n at org.lsposed.lspatch.loader.LSPApplication.onLoad(LSPApplication.java:115)\n'
    def testFrameworkCollisionRequiresOwningStackAndDoesNotPrintRawTokens(self):
        r=self.report({'logs/verbose_new.log':self.stack()+'privateToken=DO_NOT_OUTPUT\n'})
        self.assertTrue(r['frameworkKotlinCollision']);self.assertEqual(1,r['newVerboseFailureEvents'])
        self.assertNotIn('DO_NOT_OUTPUT',json.dumps(r));self.assertFalse(r['module139EntryConfirmed'])
    def testUnrelatedHostMissingMethodIsNotClassifiedAsVector(self):
        r=self.report({'logs/verbose_new.log':module.COLLISION+'\n at unrelated.host.App.start(App.java:1)'})
        self.assertFalse(r['frameworkKotlinCollision'])
    def testTransferIsNotRuntimeLoad(self):
        r=self.report({'logs/verbose_new.log':'fileName=Toki-2.1.19-debug.apk\n'})
        self.assertTrue(r['module139ApkTransferOnly']);self.assertFalse(r['module139EntryConfirmed'])
    def testHistoricalAndNewLogsAreSeparateAndRepeatedStacksDeduplicate(self):
        r=self.report({'logs/framework_old.log':self.stack(),'logs/verbose_new.log':self.stack()+self.stack()})
        self.assertEqual(1,r['newVerboseFailureEvents']);self.assertEqual(1,r['historicalFrameworkFailureEvents'])
    def testShizukuAuthorizationDoesNotProveFrameworkOrModuleStarted(self):
        r=self.report({'device.txt':'LSPatch: 1.2 (487)\nAndroid: 10 (API 29)\nVector: canary-3106\n','shizuku.txt':'Granted: true\nBinder available: true\n'})
        self.assertTrue(r['shizukuGranted']);self.assertTrue(r['binderAvailable']);self.assertEqual(29,r['androidApi'])
        self.assertFalse(r['module139EntryConfirmed']);self.assertFalse(r['frameworkKotlinCollision'])

if __name__=='__main__':unittest.main()
