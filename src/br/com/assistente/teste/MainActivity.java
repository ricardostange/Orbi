package br.com.assistente.teste;

import android.app.Activity;
import android.app.role.RoleManager;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.*;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.view.*;
import android.widget.*;
import org.json.JSONArray;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends Activity {
    private static final int SPEECH=100, ROLE=101;
    private final int bg=Color.rgb(14,25,35), accent=Color.rgb(183,245,138);
    private TextView status, heard, answer, trace;
    private EditText input;
    private TextToSpeech tts;
    private boolean ttsReady, listening;
    private String pendingSpeech;
    private int dp(int x){return (int)(x*getResources().getDisplayMetrics().density+0.5f);}
    private TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(8),0,dp(8));return t;}
    private void button(LinearLayout root,String label,Runnable action){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextColor(bg);b.setBackgroundTintList(ColorStateList.valueOf(accent));root.addView(b,new LinearLayout.LayoutParams(-1,dp(56)));b.setOnClickListener(v->action.run());}
    private boolean assist(Intent i){return Intent.ACTION_ASSIST.equals(i.getAction())||"android.intent.action.VOICE_ASSIST".equals(i.getAction());}
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        getWindow().setStatusBarColor(bg);getWindow().setNavigationBarColor(bg);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(bg);
        LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(dp(24),dp(20),dp(24),dp(24));scroll.addView(root);
        root.addView(text("PROTÓTIPO 02 · ANDROID",12,accent));root.addView(text("Orbi",38,Color.WHITE));
        root.addView(text("Seu botão chama. Você fala. Uma ferramenta executa.",17,Color.LTGRAY));
        status=text("Pronto para ouvir",16,accent);root.addView(status);
        button(root,"Falar agora",()->listen());
        button(root,"Selecionar como assistente padrão",()->chooseAssistant());
        root.addView(text("Depois de selecionar este app, teste o botão que você já configurou. O atalho precisa chamar o assistente padrão, e não um app específico.",14,Color.LTGRAY));
        input=new EditText(this);input.setSingleLine(true);input.setTextColor(Color.WHITE);input.setHintTextColor(Color.GRAY);input.setHint("Ou digite um comando");root.addView(input);
        button(root,"Executar texto",()->execute(input.getText().toString()));
        heard=text("",15,Color.LTGRAY);answer=text("Diga “olá mundo”, “que horas são?”, “anotar comprar café” “ler notas” ou “remover comprar café”.",20,Color.WHITE);trace=text("Ferramentas locais · sem LLM nesta versão",13,accent);
        root.addView(heard);root.addView(answer);root.addView(trace);
        root.addView(text("Para remover, diga “remover” seguido do texto exato: maiúsculas, acentos e pontuação contam. Se houver notas iguais, remove apenas a primeira. Notas ficam somente neste app. A transcrição é feita pelo serviço de voz do celular e pode usar internet. Não há escuta contínua.",13,Color.LTGRAY));
        setContentView(scroll);
        tts=new TextToSpeech(this,result->{
            if(result==TextToSpeech.SUCCESS){int lang=tts.setLanguage(new Locale("pt","BR"));ttsReady=lang>=0;if(ttsReady&&pendingSpeech!=null&&!listening){say(pendingSpeech);pendingSpeech=null;}}
        });
        if(saved!=null){listening=saved.getBoolean("listening");heard.setText(saved.getString("heard",""));answer.setText(saved.getString("answer",""));trace.setText(saved.getString("trace",""));}
        if(saved==null && assist(getIntent()))root.post(()->listen());
    }
    @Override protected void onNewIntent(Intent i){super.onNewIntent(i);setIntent(i);if(assist(i))listen();}
    private void chooseAssistant(){
        try{
            if(Build.VERSION.SDK_INT>=29){RoleManager r=(RoleManager)getSystemService(ROLE_SERVICE);if(r!=null&&r.isRoleAvailable(RoleManager.ROLE_ASSISTANT)&&!r.isRoleHeld(RoleManager.ROLE_ASSISTANT)){startActivityForResult(r.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT),ROLE);return;}}
            startActivity(new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS));
        }catch(ActivityNotFoundException|SecurityException e){try{startActivity(new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS));}catch(Exception ignored){status.setText("Abra Ajustes > Apps > Apps padrão > Assistente digital.");}}
    }
    private void listen(){
        if(listening)return;
        if(tts!=null)tts.stop();pendingSpeech=null;
        Intent i=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE,"pt-BR");i.putExtra(RecognizerIntent.EXTRA_PROMPT,"Diga um comando para o Orbi");i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,1);
        try{listening=true;status.setText("Ouvindo…");startActivityForResult(i,SPEECH);}catch(ActivityNotFoundException|SecurityException e){listening=false;status.setText("Reconhecimento de voz indisponível. Use o campo de texto.");}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==SPEECH){listening=false;ArrayList<String> values=data==null?null:data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if(result==RESULT_OK&&values!=null&&!values.isEmpty())execute(values.get(0));else status.setText("Nenhuma fala recebida. Tente novamente.");
        }else if(request==ROLE){status.setText(result==RESULT_OK?"Assistente selecionado. Teste seu botão.":"Seleção não concluída. Você ainda pode testar por aqui.");}
    }
    private JSONArray notes(){try{return new JSONArray(getPreferences(0).getString("notes","[]"));}catch(Exception e){return new JSONArray();}}
    private void execute(String raw){
        if(raw.trim().isEmpty()){status.setText("Fale ou digite um comando.");return;}
        CommandRouter.Command c=CommandRouter.parse(raw);String response;
        switch(c.tool){
            case "hello":response="Olá, mundo! Seu assistente está funcionando.";break;
            case "time":response="Agora são "+new SimpleDateFormat("HH:mm",new Locale("pt","BR")).format(new Date())+".";break;
            case "save_note":
                JSONArray a=notes();if(a.length()>=100){response="Limite de 100 notas neste protótipo.";break;}
                if(c.argument.length()>2000){response="Use uma nota com até 2.000 caracteres.";break;}
                a.put(c.argument);boolean ok=getPreferences(0).edit().putString("notes",a.toString()).commit();response=ok?"Anotado: "+c.argument:"Não foi possível salvar a nota.";break;
            case "remove_note":
                JSONArray existing=notes();
                java.util.List<String> values=new java.util.ArrayList<String>();
                for(int n=0;n<existing.length();n++)values.add(existing.optString(n));
                int match=NoteMatcher.firstExact(values,c.argument);
                if(match<0){response="Não encontrei uma nota com esse texto exato. Nenhuma nota foi removida.";break;}
                JSONArray remaining=new JSONArray();
                for(int n=0;n<values.size();n++)if(n!=match)remaining.put(values.get(n));
                boolean removed=getPreferences(0).edit().putString("notes",remaining.toString()).commit();
                response=removed?"Removido: "+c.argument:"Não foi possível salvar a remoção. Tente novamente.";break;
            case "list_notes":
                JSONArray list=notes();StringBuilder b=new StringBuilder();int start=Math.max(0,list.length()-10);
                for(int n=start;n<list.length();n++)b.append(n+1).append(". ").append(list.optString(n)).append("\n");
                response=list.length()==0?"Você ainda não tem notas.":(start>0?"Últimas 10 notas:\n":"Suas notas:\n")+b.toString();break;
            case "help":response="Experimente: olá mundo; que horas são; anotar comprar café; ler notas; remover comprar café.";break;
            default:response="Ainda não entendi esse comando. Este teste usa comandos fixos. Diga ajuda para ouvir os exemplos.";
        }
        heard.setText("Você: "+raw);answer.setText(response);trace.setText("Ferramenta: "+c.tool);status.setText("Pronto para o próximo comando");say(response);
    }
    private void say(String s){if(ttsReady){tts.speak(s.substring(0,Math.min(s.length(),TextToSpeech.getMaxSpeechInputLength())),TextToSpeech.QUEUE_FLUSH,null,"reply");}else pendingSpeech=s;}
    @Override protected void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);b.putBoolean("listening",listening);b.putString("heard",heard.getText().toString());b.putString("answer",answer.getText().toString());b.putString("trace",trace.getText().toString());}
    @Override protected void onDestroy(){if(tts!=null){tts.stop();tts.shutdown();}super.onDestroy();}
}
