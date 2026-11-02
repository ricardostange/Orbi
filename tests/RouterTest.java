import br.com.assistente.teste.CommandRouter;
public class RouterTest {
 public static void main(String[] args) {
  check("Olá mundo!","hello","");check("Que horas são?","time","");
  check("anotar comprar café","save_note","comprar café");
  check("Anote reunião às 14h","save_note","reunião às 14h");
  check("ler notas","list_notes","");check("apagar tudo","unknown","");
  check("anotar","unknown","");check(null,"unknown","");
  check("remover comprar café","remove_note","comprar café");
  check("Remover Comprar café!","remove_note","Comprar café!");
  check("remover","unknown","");check("remover   ","unknown","");
  java.util.List<String> notes=java.util.Arrays.asList("comprar café","Comprar café","comprar café","comprar café!");
  match(notes,"comprar café",0);match(notes,"Comprar café",1);
  match(notes,"comprar cafe",-1);match(notes,"café",-1);
  match(notes,"comprar café!",3);match(notes,"comprar  café",-1);
  match(notes,"",-1);match(java.util.Collections.<String>emptyList(),"comprar café",-1);
  System.out.println("20 routing and exact-match tests passed");
 }
 static void match(java.util.List<String> notes,String target,int expected) {
  int actual=br.com.assistente.teste.NoteMatcher.firstExact(notes,target);
  if(actual!=expected)throw new AssertionError(target+": "+actual);
 }
 static void check(String text,String tool,String arg) {
  CommandRouter.Command c=CommandRouter.parse(text);
  if(!c.tool.equals(tool)||!c.argument.equals(arg))throw new AssertionError(text+": "+c.tool);
 }
}
