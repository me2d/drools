package org.drools.games.pong;

import org.kie.api.KieBaseConfiguration;
import org.kie.internal.KnowledgeBase;
import org.kie.internal.KnowledgeBaseFactory;
import org.kie.internal.builder.KnowledgeBuilder;
import org.kie.internal.builder.KnowledgeBuilderFactory;
import org.kie.api.conf.EventProcessingOption;
import org.kie.internal.runtime.StatefulKnowledgeSession;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.kie.internal.io.ResourceFactory.newClassPathResource;
import static org.kie.api.io.ResourceType.DRL;

public class PongMain {

    /**
     * @param args
     */
    public static void main(String[] args) {
        new PongMain().init(true);
    }

    public PongMain() {
    }

    public void init(boolean exitOnClose) {
        KnowledgeBuilder kbuilder = KnowledgeBuilderFactory.newKnowledgeBuilder();

        kbuilder.batch().add( newClassPathResource( "init.drl", getClass()  ), DRL )
                        .add( newClassPathResource( "game.drl",  getClass()  ), DRL )
                        .add( newClassPathResource( "keys.drl",  getClass()  ), DRL )
                        .add( newClassPathResource( "move.drl",  getClass()  ), DRL )
                        .add( newClassPathResource( "collision.drl",  getClass()  ), DRL )
                        .add( newClassPathResource( "ui.drl", getClass() ), DRL ).build();   
        if ( kbuilder.hasErrors() ) {
            throw new RuntimeException( kbuilder.getErrors().toString() );
        }
        
        KieBaseConfiguration config = KnowledgeBaseFactory.newKnowledgeBaseConfiguration();
        config.setOption( EventProcessingOption.STREAM );
        
        KnowledgeBase kbase = KnowledgeBaseFactory.newKnowledgeBase( config );        
        kbase.addKnowledgePackages( kbuilder.getKnowledgePackages() );
        
        

        
        StatefulKnowledgeSession ksession = kbase.newStatefulKnowledgeSession();
        //ksession.addEventListener( new DebugAgendaEventListener() );
        PongConfiguration pconf = new PongConfiguration();
        pconf.setExitOnClose(exitOnClose);
        ksession.setGlobal("pconf", pconf);
        
//        ksession.addEventListener( new DefaultAgendaEventListener() {
//            public void beforeMatchFired(BeforeActivationFiredEvent event)  {
//                System.out.println( "b: " + event.getActivation().getRule().getName() + " : " + event.getActivation().toFactHandles() );
//            }        
//            public void afterMatchFired(AfterActivationFiredEvent event)  {
//                System.out.println( "a: " + event.getActivation().getRule().getName() + " : " + event.getActivation().toFactHandles() );
//            }
////            public void matchCreated(ActivationCreatedEvent event)  {
////                System.out.println( "cr: " + event.getActivation().getRule().getName() + " : " + event.getActivation().toFactHandles() );
////            }
////            public void matchCancelled(ActivationCancelledEvent event)  {
////                System.out.println( "cl: " + event.getActivation().getRule().getName() + " : " + event.getActivation().toFactHandles() );
////            }                      
//            
//        });

        runKSession(ksession);
    }

    public void runKSession(final StatefulKnowledgeSession ksession) {
        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.submit(new Runnable() {
            public void run() {
                // run forever
                ksession.fireUntilHalt();
            }
        });
    }

}
