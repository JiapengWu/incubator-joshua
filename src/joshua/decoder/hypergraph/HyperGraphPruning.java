/* This file is part of the Joshua Machine Translation System.
 * 
 * Joshua is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation; either version 2.1 of the License, or 
 * (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful, but 
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this library; if not, write to the Free Software Foundation,
 * Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA
 */
package joshua.decoder.hypergraph;

import joshua.decoder.Decoder;
import joshua.decoder.Symbol;
import joshua.decoder.hypergraph.DiskHyperGraph;
import joshua.decoder.hypergraph.HyperGraph;
import joshua.decoder.hypergraph.HyperGraph.Deduction;
import joshua.decoder.hypergraph.HyperGraph.Item;
import joshua.util.FileUtility;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * during the pruning process, many Item/Deductions may not be explored at all due to the early-stop in pruning_deduction
 * 
 * @author Zhifei Li, <zhifei.work@gmail.com>
 * @version $LastChangedDate$
 */
public class HyperGraphPruning extends InsideOutside {
	HashMap tbl_processed_items = new HashMap();
	double best_cost;//viterbi cost in the hypergraph
	
	
	boolean fix_threshold_pruning = true;
	double THRESHOLD_GENERAL = 10;//if the merit is worse than the best_cost by this number, then prune
	double THRESHOLD_GLUE = 10;//if the merit is worse than the best_cost by this number, then prune
	double CUR_THRESHOLD_GENERAL = THRESHOLD_GENERAL;
	double CUR_THRESHOLD_GLUE = THRESHOLD_GLUE;
	double THRESHOLD_STEP_GENERAL = 1;
	double THRESHOLD_STEP_GLUE = 1;
	int num_survived_deduction = 0;
	int num_survived_item = 0;
	
	int glue_grammar_owner=0;//TODO
	
//####	
	
	public HyperGraphPruning(boolean fix_threshold, double threshold_general, double threshold_glue, double step_general, double step_glue){
		fix_threshold_pruning = fix_threshold;
		THRESHOLD_GENERAL = threshold_general;
		THRESHOLD_GLUE = threshold_glue;
		CUR_THRESHOLD_GENERAL = THRESHOLD_GENERAL;
		CUR_THRESHOLD_GLUE = THRESHOLD_GLUE;
		THRESHOLD_STEP_GENERAL = step_general;
		THRESHOLD_STEP_GLUE = step_glue;
		glue_grammar_owner = Symbol.add_terminal_symbol(Decoder.begin_mono_owner);//TODO
	}
	
	
	//used by inside-outside estimation
	protected  double get_deduction_score(Deduction dt, Item parent_it){
		return dt.get_transition_cost(false);//TODO this is very bad in terms of computation
	}
	
	public void clear_state(){
		tbl_processed_items.clear();
		super.clear_state();
	}
	

//	######################### pruning here ##############
	public void pruning_hg(HyperGraph hg){
		run_inside_outside(hg, 1, 1);//viterbi-min, log-semiring
		if(fix_threshold_pruning){
			pruning_hg_real(hg);
			super.clear_state();
		}else{
			System.out.println("wrong call"); System.exit(0);
		}			
	}
	
	private void  pruning_hg_real(HyperGraph hg){
		best_cost = (Double)tbl_inside_cost.get(hg.goal_item);//set the best_cost
		
		num_survived_deduction = 0;
		num_survived_item = 0;
		tbl_processed_items.clear(); 
		pruning_item(hg.goal_item);
		
		//clear up
		tbl_processed_items.clear();
		
		System.out.println("Item suvived ratio: "+ num_survived_item*1.0/hg.num_items + " =  " + num_survived_item + "/" + hg.num_items);
		System.out.println("Deduct suvived ratio: "+ num_survived_deduction*1.0/hg.num_deduction + " =  " + num_survived_deduction + "/" + hg.num_deduction);
	}
		
	
	private void pruning_item(Item it){
		if(tbl_processed_items.containsKey(it))return;
		tbl_processed_items.put(it,true);
		boolean should_surive=false;
		//### recursive call on each deduction
		for(int i=0; i < it.l_deductions.size(); i++){
			Deduction dt = (Deduction) it.l_deductions.get(i);
			boolean surived = pruning_deduction(dt, it);//deduction-specifc operation
			if(surived) 
				should_surive=true;//at least one deduction survive
			else{
				it.l_deductions.remove(i);
				i--;
			}			
		}
		//TODO: now we simply remove the pruned deductions, but in general, we may want to update the variables mainted in the item (e.g., best_deduction); this depends on the pruning method used
		
		/*by defintion: "should_surive==false" should be impossible, since if I got called, then my upper-deduction must survive, then i will survive
		* because there must be one way to reach me from lower part in order for my upper-deduction survive*/
		if(should_surive==false){
			System.out.println("item explored but does not survive");System.exit(0);//TODO: since we always keep the best_deduction, this should never be true
		}else{
			num_survived_item++;
		}
	}
	
	
	
	//if survive, return true
	//best-deduction is always kept
	private boolean pruning_deduction(Deduction dt, Item parent){
		//TODO: theoretically, if an item is get called, then its best deduction should always be kept even just by the threshold-checling. In reality, due to precision of Double, the threshold-checking may not be perfect
		if(dt != parent.best_deduction){//best deduction should always survive if the Item is get called
			//### get inside cost of all my ant-items
			double inside = ONE_IN_SEMIRING;
			if(dt.get_ant_items()!=null){
				for(Item ant_it : dt.get_ant_items())
					inside = multi_in_semiring(inside,(Double)tbl_inside_cost.get(ant_it));
			}
			
			//### prune?
			if(should_prune_deduction(dt, parent, inside)){
				return false;//early stop
			}
		}
		
		//### still survive, recursive call all my ant-items
		if(dt.get_ant_items()!=null){
			for(Item ant_it : dt.get_ant_items())
				pruning_item(ant_it);//recursive call on each ant item, note: the ant_it will not be pruned as I need it
		}
		
		//### if get to here, then survive; remember: if I survive, then my upper-item must survive
		num_survived_deduction++;
		return true;//survive
	}
	 
	private boolean should_prune_deduction(Deduction dt, Item parent, double inside){
		//### get merit
		double outside = (Double)tbl_outside_cost.get(parent);
		double merit = multi_in_semiring(inside, outside);
		merit = multi_in_semiring(merit, get_deduction_score(dt, parent));
		
		//sanity check
		//if(merit>worst_cost || merit < best_cost){System.out.println("merit is not between best and worst, best: " + best_cost +"; worset:" + worst_cost + "; merit: " + merit); System.exit(0);}
		 
		if(dt.get_rule()!=null && dt.get_rule().owner == glue_grammar_owner && dt.get_rule().arity==2){//specicial rule: S->S X
			//TODO
			return (merit-best_cost>CUR_THRESHOLD_GLUE) ? true:false;
		}else{		
			return (merit-best_cost>CUR_THRESHOLD_GENERAL) ? true:false;
		}		
	}
	
	
	//############# not used ##############
	//for all objects, only create a new l_deductions, others are just pointers
	private static Item clone_item_minimum(Item it_in, ArrayList<Deduction> l_deductions){
		return new Item(it_in.i, it_in.j, it_in.lhs,  l_deductions, it_in.best_deduction, it_in.tbl_states);
	}
	
}
