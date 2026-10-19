package io.github.meiyongai.toki.subtitle.hook;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
import org.jf.dexlib2.Opcode;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.immutable.*;
import org.jf.dexlib2.immutable.instruction.*;
import org.jf.dexlib2.immutable.reference.*;

public class CommentGateIndexTest {
    private static final String OWNER="LX/NewGate;",SUPPLIER="LX/NewSupplier;",LAZY="LX/NewLazy;";
    private ImmutableMethod method(String name,String result,List<ImmutableMethodParameter> parameters,Instruction... code) {
        return new ImmutableMethod(OWNER,name,parameters,result,9,Set.of(),Set.of(),
            new ImmutableMethodImplementation(2,List.of(code),List.of(),List.of()));
    }
    private ImmutableInstruction35c call(String owner,String name,List<String> params,String result) {
        return new ImmutableInstruction35c(Opcode.INVOKE_STATIC,0,0,0,0,0,0,new ImmutableMethodReference(owner,name,params,result));
    }
    private ImmutableClassDef gate(boolean forward,boolean wrongPreference) {
        var field=new ImmutableFieldReference(OWNER,"renamed",LAZY);
        var init=method("<clinit>","V",List.of(),
            new ImmutableInstruction21c(Opcode.NEW_INSTANCE,0,new ImmutableTypeReference(wrongPreference?"LX/Other;":SUPPLIER)),
            call("LX/Factory;","make",List.of("Lkotlin/jvm/functions/Function0;"),LAZY),
            new ImmutableInstruction21c(Opcode.SPUT_OBJECT,0,field),new ImmutableInstruction10x(Opcode.RETURN_VOID));
        var reader=method("read", "Z",List.of(),new ImmutableInstruction21c(Opcode.SGET_OBJECT,0,field),
            call(LAZY,"getValue",List.of(),"Ljava/lang/Object;"),call("Ljava/lang/Boolean;","booleanValue",List.of(),"Z"),new ImmutableInstruction11x(Opcode.RETURN,0));
        var zero=method("enabled","Z",List.of(),call(forward?OWNER:"LX/Other;","read",List.of(),"Z"),new ImmutableInstruction11x(Opcode.RETURN,0));
        var one=method("allowed","Z",List.of(new ImmutableMethodParameter("Z",Set.of(),null)),call(OWNER,"read",List.of(),"Z"),new ImmutableInstruction11x(Opcode.RETURN,0));
        return new ImmutableClassDef(OWNER,1,"Ljava/lang/Object;",List.of(),null,Set.of(),List.of(),List.of(init,reader,zero,one));
    }
    @Test public void renamedGateRequiresPreferenceLazyReaderAndBothCallers() {
        assertEquals(List.of("allowed(Z)Z","enabled()Z","read()Z"),CommentGateIndex.contract(gate(true,false),Set.of(SUPPLIER)));
        assertTrue(CommentGateIndex.contract(gate(false,false),Set.of(SUPPLIER)).isEmpty());
        assertTrue(CommentGateIndex.contract(gate(true,true),Set.of(SUPPLIER)).isEmpty());
        assertTrue(CommentGateIndex.contract(gate(true,false),Set.of("LX/Other;")).isEmpty());
    }
}
