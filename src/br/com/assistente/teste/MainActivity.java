package br.com.assistente.teste;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.*;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.view.*;
import android.widget.*;
import org.json.JSONArray;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends Activity {
    private static final int SPEECH=100;
    private static final String COMMANDS="Você pode dizer:\n\n"
        + "• Olá mundo\n"
        + "• Que horas são?\n"
        + "• Anotar comprar café\n"
        + "• Ler notas\n"
        + "• Remover comprar café\n"
        + "• Ajuda — mostrar os comandos\n\n"
        + "Troque “comprar café” pelo texto da sua nota. Para remover, use o texto exato, incluindo maiúsculas, acentos e pontuação.";
    private final int bg=Color.rgb(14,25,35), accent=Color.rgb(183,245,138);
    private TextView answer;
    private Button speak;
    private ScrollView scroll;
    private TextToSpeech tts;
    private boolean ttsReady, listening;
    private String pendingSpeech;
    private int dp(int x){return (int)(x*getResources().getDisplayMetrics().density+0.5f);}
    private TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(8),0,dp(8));return t;}
    private boolean assist(Intent i){return Intent.ACTION_ASSIST.equals(i.getAction())||"android.intent.action.VOICE_ASSIST".equals(i.getAction());}
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        getWindow().setStatusBarColor(bg);getWindow().setNavigationBarColor(bg);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);
        root.setPadding(dp(24),dp(24),dp(24),dp(24));
        scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        answer=text(COMMANDS,20,Color.WHITE);
        answer.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        scroll.addView(answer);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        speak=new Button(this);
        speak.setText("Falar");
        speak.setAllCaps(false);
        speak.setTextSize(18);
        speak.setTextColor(bg);
        speak.setBackgroundTintList(ColorStateList.valueOf(accent));
        LinearLayout.LayoutParams buttonParams=new LinearLayout.LayoutParams(-1,-2);
        buttonParams.topMargin=dp(16);
        speak.setMinHeight(dp(56));
        root.addView(speak,buttonParams);
        speak.setOnClickListener(v->listen());
        Button settings=new Button(this);
        settings.setText("Configurar API");
        settings.setAllCaps(false);
        root.addView(settings,new LinearLayout.LayoutParams(-1,-2));
        settings.setOnClickListener(v->configureApi());
        setContentView(root);
        tts=new TextToSpeech(this,result->{
            if(result==TextToSpeech.SUCCESS){int lang=tts.setLanguage(new Locale("pt","BR"));ttsReady=lang>=0;if(ttsReady&&pendingSpeech!=null&&!listening){say(pendingSpeech);pendingSpeech=null;}}
        });
        if(saved!=null){
            listening=saved.getBoolean("listening");
            answer.setText(saved.getString("answer",COMMANDS));
            speak.setEnabled(!listening);
        }
        if(saved==null && assist(getIntent()))root.post(()->listen());
    }
    @Override protected void onNewIntent(Intent i){super.onNewIntent(i);setIntent(i);if(assist(i))listen();}
    private void configureApi(){
        if(tts!=null)tts.stop();
        pendingSpeech=null;
        final ApiKeyStore store=new ApiKeyStore(this);
        LinearLayout content=new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24),dp(8),dp(24),0);
        TextView info=new TextView(this);
        info.setText((store.hasKey()?"Chave salva. Cole outra para substituir.":"Cole sua chave de API do DeepSeek.")
            + "\nA chave fica criptografada neste celular. A conexão com o DeepSeek ainda não está ativa.");
        content.addView(info);
        EditText keyInput=new EditText(this);
        keyInput.setHint("Chave de API");
        keyInput.setSingleLine(true);
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyInput.setSaveEnabled(false);
        if(Build.VERSION.SDK_INT>=26)keyInput.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        content.addView(keyInput,new LinearLayout.LayoutParams(-1,-2));
        AlertDialog dialog=new AlertDialog.Builder(this)
            .setTitle("DeepSeek")
            .setView(content)
            .setPositiveButton("Salvar",null)
            .setNegativeButton("Cancelar",null)
            .setNeutralButton("Remover chave",null)
            .create();
        dialog.setOnDismissListener(d->keyInput.getText().clear());
        dialog.setOnShowListener(d->{
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(store.hasKey());
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                String value=keyInput.getText().toString().trim();
                if(value.isEmpty()){keyInput.setError("Cole uma chave para salvar.");return;}
                if(value.matches("(?s).*\\s.*")){keyInput.setError("A chave não deve conter espaços ou quebras de linha.");return;}
                try{
                    store.save(value);
                    Toast.makeText(this,"Chave salva neste celular.",Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                }catch(Exception e){keyInput.setError("Não foi possível salvar. Tente novamente.");}
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{
                try{
                    store.remove();
                    Toast.makeText(this,"Chave removida.",Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                }catch(Exception e){keyInput.setError("Não foi possível remover. Tente novamente.");}
            });
        });
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.show();
    }
    private void show(String message){
        answer.setText(message);
        scroll.post(()->scroll.scrollTo(0,0));
    }
    private void listen(){
        if(listening)return;
        if(tts!=null)tts.stop();pendingSpeech=null;
        Intent i=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE,"pt-BR");i.putExtra(RecognizerIntent.EXTRA_PROMPT,"Diga um comando para o Orbi");i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,1);
        try{listening=true;speak.setEnabled(false);startActivityForResult(i,SPEECH);}catch(ActivityNotFoundException|SecurityException e){listening=false;speak.setEnabled(true);show("Reconhecimento de voz indisponível. Verifique o serviço de voz nas configurações do celular.");}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==SPEECH){listening=false;speak.setEnabled(true);ArrayList<String> values=data==null?null:data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if(result==RESULT_OK&&values!=null&&!values.isEmpty())execute(values.get(0));else show("Nenhuma fala recebida. Toque em Falar para tentar novamente.\n\n"+COMMANDS);
        }
    }
    private JSONArray notes(){try{return new JSONArray(getPreferences(0).getString("notes","[]"));}catch(Exception e){return new JSONArray();}}
    private void execute(String raw){
        if(raw.trim().isEmpty()){show("Toque em Falar e diga um comando.");return;}
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
            case "help":response=COMMANDS;break;
            default:response="Não entendi. Diga ajuda para consultar os comandos.";
        }
        show(response);say(response);
    }
    private void say(String s){if(ttsReady){tts.speak(s.substring(0,Math.min(s.length(),TextToSpeech.getMaxSpeechInputLength())),TextToSpeech.QUEUE_FLUSH,null,"reply");}else pendingSpeech=s;}
    @Override protected void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);b.putBoolean("listening",listening);b.putString("answer",answer.getText().toString());}
    @Override protected void onDestroy(){if(tts!=null){tts.stop();tts.shutdown();}super.onDestroy();}
}
