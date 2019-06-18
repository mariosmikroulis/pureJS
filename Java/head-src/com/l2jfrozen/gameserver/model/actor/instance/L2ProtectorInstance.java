/* L2jFrozen Project - www.l2jfrozen.com 
 * 
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2, or (at your option)
 * any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA
 * 02111-1307, USA.
 *
 * http://www.gnu.org/copyleft/gpl.html
 */
package com.l2jfrozen.gameserver.model.actor.instance;

import java.util.concurrent.ScheduledFuture;

import com.l2jfrozen.Config;
import com.l2jfrozen.gameserver.ai.CtrlIntention;
import com.l2jfrozen.gameserver.datatables.SkillTable;
import com.l2jfrozen.gameserver.model.L2Character;
import com.l2jfrozen.gameserver.model.L2Skill;
import com.l2jfrozen.gameserver.model.L2Summon;
import com.l2jfrozen.gameserver.network.serverpackets.CreatureSay;
import com.l2jfrozen.gameserver.network.serverpackets.MagicSkillUser;
import com.l2jfrozen.gameserver.network.serverpackets.ActionFailed;
import com.l2jfrozen.gameserver.network.serverpackets.MyTargetSelected;
import com.l2jfrozen.gameserver.network.serverpackets.NpcHtmlMessage;
import com.l2jfrozen.gameserver.network.serverpackets.ValidateLocation;
import com.l2jfrozen.gameserver.templates.L2NpcTemplate;
import com.l2jfrozen.gameserver.thread.ThreadPoolManager;

import javolution.text.TextBuilder;

/**
 * @author Ederik
 */
public class L2ProtectorInstance extends L2NpcInstance
{
	private ScheduledFuture<?> _aiTask;
	
	private class ProtectorAI implements Runnable
	{
		private final L2ProtectorInstance _caster;
		
		protected ProtectorAI(final L2ProtectorInstance caster)
		{
			_caster = caster;
		}
		
		@SuppressWarnings("synthetic-access")
		@Override
		public void run()
		{
			/**
			 * For each known player in range, cast sleep if pvpFlag != 0 or Karma >0 Skill use is just for buff animation
			 */
			for (final L2PcInstance player : getKnownList().getKnownPlayers().values())
			{
				if (player.getKarma() > 0 && Config.PROTECTOR_PLAYER_PK || player.getPvpFlag() != 0 && Config.PROTECTOR_PLAYER_PVP)
				{
					LOGGER.warn("player: " + player);
					handleCast(player, Config.PROTECTOR_SKILLID, Config.PROTECTOR_SKILLLEVEL);
				}
				final L2Summon activePet = player.getPet();
				
				if (activePet == null)
					continue;
				
				if (activePet.getKarma() > 0 && Config.PROTECTOR_PLAYER_PK || activePet.getPvpFlag() != 0 && Config.PROTECTOR_PLAYER_PVP)
				{
					LOGGER.warn("activePet: " + activePet);
					handleCastonPet(activePet, Config.PROTECTOR_SKILLID, Config.PROTECTOR_SKILLLEVEL);
				}
			}
		}
		
		// Cast for Player
		private boolean handleCast(final L2PcInstance player, final int skillId, final int skillLevel)
		{
			if (player.isGM() || player.isDead() || !player.isVisible() || !isInsideRadius(player, Config.PROTECTOR_RADIUS_ACTION, false, false))
				return false;
			
			L2Skill skill = SkillTable.getInstance().getInfo(skillId, skillLevel);
			
			if (player.getFirstEffect(skill) == null)
			{
				final int objId = _caster.getObjectId();
				skill.getEffects(_caster, player, false, false, false);
				broadcastPacket(new MagicSkillUser(_caster, player, skillId, skillLevel, Config.PROTECTOR_SKILLTIME, 0));
				broadcastPacket(new CreatureSay(objId, 0, String.valueOf(getName()), Config.PROTECTOR_MESSAGE));
				
				skill = null;
				return true;
			}
			
			return false;
		}
		
		// Cast for pet
		private boolean handleCastonPet(final L2Summon player, final int skillId, final int skillLevel)
		{
			if (player.isDead() || !player.isVisible() || !isInsideRadius(player, Config.PROTECTOR_RADIUS_ACTION, false, false))
				return false;
			
			L2Skill skill = SkillTable.getInstance().getInfo(skillId, skillLevel);
			if (player.getFirstEffect(skill) == null)
			{
				final int objId = _caster.getObjectId();
				skill.getEffects(_caster, player, false, false, false);
				broadcastPacket(new MagicSkillUser(_caster, player, skillId, skillLevel, Config.PROTECTOR_SKILLTIME, 0));
				broadcastPacket(new CreatureSay(objId, 0, String.valueOf(getName()), Config.PROTECTOR_MESSAGE));
				
				skill = null;
				return true;
			}
			
			return false;
		}
	}
	
	public L2ProtectorInstance(final int objectId, final L2NpcTemplate template)
	{
		super(objectId, template);
		
		if (_aiTask != null)
		{
			_aiTask.cancel(true);
		}
		
		_aiTask = ThreadPoolManager.getInstance().scheduleAiAtFixedRate(new ProtectorAI(this), 3000, 3000);
	}
	
	@Override
	public void deleteMe()
	{
		if (_aiTask != null)
		{
			_aiTask.cancel(true);
			_aiTask = null;
		}
		
		super.deleteMe();
	}
	
	@Override
	public boolean isAutoAttackable(final L2Character attacker)
	{
		return false;
	}
	
	@Override
	public void onAction(L2PcInstance player)
	{
		if (!canTarget(player))
		{
			return;
		}
		
		if (this != player.getTarget())
		{
			player.setTarget(this);
			
			player.sendPacket(new MyTargetSelected(getObjectId(), 0));
			
			player.sendPacket(new ValidateLocation(this));
		}
		else if (!canInteract(player))
		{
			player.getAI().setIntention(CtrlIntention.AI_INTENTION_INTERACT, this);
		}
		else
		{
			showHtmlWindow(player);
		}
		
		player.sendPacket(new ActionFailed());
	}
	
	private void showHtmlWindow(L2PcInstance activeChar)
	 {
		NpcHtmlMessage nhm = new NpcHtmlMessage(5);
		TextBuilder replyMSG = new TextBuilder("");
	
		replyMSG.append("<html><title>L2Submission!</title><body><center><br><font color=\"003366\" align=\"center\">___________________________________________</font><img src=L2Submission.protector height=90 width=256><br1><font color=\"003366\" align=\"center\">___________________________________________</font><br1>");
		replyMSG.append("Hello! If you are planning to farm but you don't<br1> want someone to annoying you while you are farming,<br1> you are in the correct place.<br1>What I do is to paralize anyone who will become flagged,<br1> so feel safe of being here!<br>");
		replyMSG.append("<font color=\"003366\" align=\"center\">___________________________________________</font></center></body></html>");
	
		nhm.setHtml(replyMSG.toString());
		activeChar.sendPacket(nhm);
	
		activeChar.sendPacket(new ActionFailed());
	}
}
