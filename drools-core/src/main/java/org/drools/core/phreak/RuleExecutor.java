package org.drools.core.phreak;

import org.drools.core.base.mvel.MVELSalienceExpression;
import org.drools.core.common.AgendaItem;
import org.drools.core.common.InternalAgenda;
import org.drools.core.common.InternalWorkingMemory;
import org.drools.core.reteoo.LeftTuple;
import org.drools.core.reteoo.PathMemory;
import org.drools.core.reteoo.RuleTerminalNode;
import org.drools.core.reteoo.RuleTerminalNodeLeftTuple;
import org.drools.core.rule.Rule;
import org.drools.core.spi.Activation;
import org.drools.core.spi.AgendaFilter;
import org.drools.core.spi.PropagationContext;
import org.drools.core.util.BinaryHeapQueue;
import org.drools.core.util.LinkedList;
import org.drools.core.util.index.LeftTupleList;

import java.util.Comparator;

public class RuleExecutor {
    private static RuleNetworkEvaluator networkEvaluator = new RuleNetworkEvaluator();
    private PathMemory      pmem;
    private RuleAgendaItem  ruleAgendaItem;
    private LeftTupleList   tupleList;
    private BinaryHeapQueue queue;
    private boolean         dirty;
    private boolean         declarativeAgendaEnabled;

    public RuleExecutor(final PathMemory pmem,
                        RuleAgendaItem ruleAgendaItem,
                        boolean declarativeAgendaEnabled) {
        this.pmem = pmem;
        this.ruleAgendaItem = ruleAgendaItem;
        this.tupleList = new LeftTupleList();
        this.declarativeAgendaEnabled = declarativeAgendaEnabled;
        if (ruleAgendaItem.getRule().getSalience().isDynamic() ) {
            queue = new BinaryHeapQueue(SalienceComparator.INSTANCE);
        }
    }

    public void evaluateNetwork(InternalWorkingMemory wm) {
        this.networkEvaluator.evaluateNetwork(pmem, null, this, wm);
        setDirty(false);
        wm.executeQueuedActions();
    }

    public int evaluateNetworkAndFire(InternalWorkingMemory wm,
                                      final AgendaFilter filter,
                                      int fireCount,
                                      int fireLimit) {
        LinkedList<StackEntry> outerStack = new LinkedList<StackEntry>();

        if (dirty) {
            this.networkEvaluator.evaluateNetwork(pmem, outerStack, this, wm);
            setDirty(false);
            wm.executeQueuedActions();
        }

        //int fireCount = 0;
        int localFireCount = 0;
        if (!tupleList.isEmpty()) {
            RuleTerminalNode rtn = (RuleTerminalNode) pmem.getNetworkNode();

            InternalAgenda agenda = (InternalAgenda) wm.getAgenda();
            int salience = ruleAgendaItem.getSalience();

            if (isDeclarativeAgendaEnabled()) {
                // Network Evaluation can notify meta rules, which should be given a chance to fire first
                RuleAgendaItem nextRule = agenda.peekNextRule();
                if (!isHighestSalience(nextRule, salience)) {
                    return localFireCount;
                }
            }

            while (!tupleList.isEmpty()) {
                LeftTuple leftTuple;
                if ( queue != null ) {
                    leftTuple = (LeftTuple)  queue.dequeue();
                    tupleList.remove(leftTuple);
                } else {
                    leftTuple = tupleList.removeFirst();
                }

                rtn = (RuleTerminalNode) leftTuple.getSink(); // branches result in multiple RTN's for a given rule, so unwrap per LeftTuple
                Rule rule = rtn.getRule();

                PropagationContext pctx = leftTuple.getPropagationContext();
                pctx = RuleTerminalNode.findMostRecentPropagationContext(leftTuple,
                                                                         pctx);

                //check if the rule is not effective or
                // if the current Rule is no-loop and the origin rule is the same then return
                if (isNotEffective(wm, rtn, rule, leftTuple, pctx)) {
                    continue;
                }

                AgendaItem item = (AgendaItem) leftTuple;
                if (agenda.getActivationsFilter() != null && !agenda.getActivationsFilter().accept(item,
                                                                                                   pctx,
                                                                                                   wm,
                                                                                                   rtn)) {
                    continue;
                }
                if (filter == null || filter.accept(item)) {
                    agenda.fireActivation(item);
                    localFireCount++;
                }

                salience = ruleAgendaItem.getSalience(); // dyanmic salience may have updated it, so get again.
                if ( queue != null && !queue.isEmpty() && salience != queue.peek().getSalience() ) {
                    ruleAgendaItem.dequeue();
                    ruleAgendaItem.setSalience( queue.peek().getSalience() );
                    ruleAgendaItem.getAgendaGroup().add( ruleAgendaItem );
                    salience = ruleAgendaItem.getSalience();
                }

                RuleAgendaItem nextRule = agenda.peekNextRule();
                if (haltRuleFiring(nextRule, fireCount, fireLimit, localFireCount, agenda, salience)) {
                    break; // another rule has high priority and is on the agenda, so evaluate it first
                }
                if (isDirty()) {
                    setDirty(false);
                    this.networkEvaluator.evaluateNetwork(pmem, outerStack, this, wm);
                }
                wm.executeQueuedActions();

                if (tupleList.isEmpty() && !outerStack.isEmpty()) {
                    // the outer stack is nodes needing evaluation, once all rule firing is done
                    // such as window expiration, which must be done serially
                    StackEntry entry = outerStack.removeFirst();
                    this.networkEvaluator.evalStackEntry(entry, outerStack, outerStack, this, wm);
                }
            }
        }

        if (!dirty && tupleList.isEmpty()) {
            ruleAgendaItem.remove();
            if (ruleAgendaItem.getRule().isEager()) {
                ((InternalAgenda) wm.getAgenda()).removeEagerRuleAgendaItem(ruleAgendaItem);
            }
        }

        return localFireCount;
    }

    public RuleTerminalNodeLeftTuple removeFirstLeftTuple() {
        RuleTerminalNodeLeftTuple lt;
        if ( queue == null ) {
            lt = (RuleTerminalNodeLeftTuple) tupleList.removeFirst();
        }   else {
            lt = (RuleTerminalNodeLeftTuple) queue.dequeue();
            removeQueuedLeftTuple(lt);

        }
        return lt;
    }

    public RuleAgendaItem getRuleAgendaItem() {
        return ruleAgendaItem;
    }

    private boolean isNotEffective(InternalWorkingMemory wm,
                                   RuleTerminalNode rtn,
                                   Rule rule,
                                   LeftTuple leftTuple,
                                   PropagationContext pctx) {
        // NB. stopped setting the LT.object to Boolean.TRUE, that Reteoo did.
        if ((!rule.isEffective(leftTuple,
                               rtn,
                               wm)) ||
            (rule.isNoLoop() && rule.equals(pctx.getRuleOrigin()))) {
            return true;
        }

        if (rule.getCalendars() != null) {
            long timestamp = wm.getSessionClock().getCurrentTime();
            for (String cal : rule.getCalendars()) {
                if (!wm.getCalendars().get(cal).isTimeIncluded(timestamp)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean haltRuleFiring(RuleAgendaItem nextRule,
                                   int fireCount,
                                   int fireLimit,
                                   int localFireCount,
                                   InternalAgenda agenda,
                                   int salience) {
        if (!agenda.continueFiring(0) || !isHighestSalience(nextRule, salience) || (fireLimit >= 0 && (localFireCount + fireCount >= fireLimit))) {
            return true;
        }
        return false;
    }

    public boolean isHighestSalience(RuleAgendaItem nextRule,
                                     int currentSalience) {
        return (nextRule == null) || nextRule.getSalience() <= currentSalience;
    }

    public void addLeftTuple(LeftTuple leftTuple) {
        ((AgendaItem)leftTuple).setQueued(true);
        this.tupleList.add(leftTuple);
        if ( queue != null ) {
            addQueuedLeftTuple(leftTuple);
        }
    }

    public void addQueuedLeftTuple(LeftTuple leftTuple) {
        int currentSalience = queue.isEmpty() ? 0 : ((Activation)queue.peek()).getSalience();
        queue.enqueue( (Activation) leftTuple );
        updateSalience(currentSalience);
    }

    public void removeLeftTuple(LeftTuple leftTuple) {
        ((AgendaItem)leftTuple).setQueued(false);
        this.tupleList.remove(leftTuple);
        if ( queue != null ) {
            removeQueuedLeftTuple(leftTuple);
        }
    }

    public void removeQueuedLeftTuple(LeftTuple leftTuple) {
        int currentSalience = queue.isEmpty() ? 0 : ((Activation)queue.peek()).getSalience();
        queue.dequeue( ((Activation) leftTuple).getQueueIndex() );
        updateSalience(currentSalience);
    }

    public void updateLeftTuple(RuleTerminalNodeLeftTuple leftTuple, int salience, PropagationContext pctx) {
        // this method is only call when dynamic salience is on for the current rule and the salience for the LeftTuple has changed
        if (salience != leftTuple.getSalience() ) {
            // saliences are different, so it must be dynamic and thus the queue is not null
            int currentSalience = queue.isEmpty() ? 0 : ((Activation)queue.peek()).getSalience();

            leftTuple.dequeue();
            queue.enqueue(leftTuple);
            updateSalience(currentSalience);
        }
        leftTuple.update(salience, pctx);
    }

    private void updateSalience(int currentSalience) {
        int newSalience = ((Activation)queue.peek()).getSalience();
        if ( currentSalience != newSalience ) {
            // salience changed, so the RuleAgendaItem needs to be removed and re-added, for sorting
            ruleAgendaItem.remove();
        }
        if ( !ruleAgendaItem.isQueued() ) {
            ruleAgendaItem.setSalience(newSalience);
            ruleAgendaItem.getAgendaGroup().add(ruleAgendaItem);
        }
    }

    public boolean isDirty() {
        return dirty;
    }

    public void setDirty(final boolean dirty) {
        this.dirty = dirty;
    }

    public boolean isDeclarativeAgendaEnabled() {
        return this.declarativeAgendaEnabled;
    }

    public static class SalienceComparator implements Comparator {
        public static final SalienceComparator INSTANCE = new SalienceComparator();

        public int compare(Object existing, Object adding) {
            RuleTerminalNodeLeftTuple rtnLt1 = (RuleTerminalNodeLeftTuple) existing;
            RuleTerminalNodeLeftTuple rtnLt2 = (RuleTerminalNodeLeftTuple) adding;

            final int s1 = rtnLt1.getSalience();
            final int s2 = rtnLt2.getSalience();

            if (s1 > s2) {
                return 1;
            } else if (s1 < s2) {
                return -1;
            } else {
                return 0;
            }
        }
    }

}
