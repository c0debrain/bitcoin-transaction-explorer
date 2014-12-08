package com.yoghurt.crypto.transactions.client.ui;

import java.util.Date;

import com.google.gwt.core.client.GWT;
import com.google.gwt.core.client.Scheduler;
import com.google.gwt.core.client.Scheduler.RepeatingCommand;
import com.google.gwt.core.client.Scheduler.ScheduledCommand;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.uibinder.client.UiHandler;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.Widget;
import com.googlecode.gwt.crypto.bouncycastle.util.encoders.Hex;
import com.googlecode.gwt.crypto.util.Str;
import com.yoghurt.crypto.transactions.client.util.BlockPartColorPicker;
import com.yoghurt.crypto.transactions.client.util.FormatUtil;
import com.yoghurt.crypto.transactions.client.widget.BlockHexViewer;
import com.yoghurt.crypto.transactions.client.widget.HashHexViewer;
import com.yoghurt.crypto.transactions.client.widget.HashViewer;
import com.yoghurt.crypto.transactions.client.widget.ValueViewer;
import com.yoghurt.crypto.transactions.shared.domain.Block;
import com.yoghurt.crypto.transactions.shared.domain.BlockPartType;
import com.yoghurt.crypto.transactions.shared.domain.RawBlockContainer;
import com.yoghurt.crypto.transactions.shared.util.ArrayUtil;
import com.yoghurt.crypto.transactions.shared.util.NumberParseUtil;
import com.yoghurt.crypto.transactions.shared.util.block.BlockEncodeUtil;
import com.yoghurt.crypto.transactions.shared.util.transaction.ComputeUtil;

public class MineViewImpl extends Composite implements MineView {
  private static final int MINING_SIMULATION_DELAY = 250;

  interface MineViewImplUiBinder extends UiBinder<Widget, MineViewImpl> {}

  private static final MineViewImplUiBinder UI_BINDER = GWT.create(MineViewImplUiBinder.class);

  @UiField(provided = true) ValueViewer versionViewer;
  @UiField(provided = true) ValueViewer previousBlockHashViewer;
  @UiField(provided = true) ValueViewer merkleRootViewer;
  @UiField(provided = true) ValueViewer timestampViewer;
  @UiField(provided = true) HashViewer bitsViewer;
  @UiField(provided = true) ValueViewer nonceViewer;

  @UiField BlockHexViewer blockHexViewer;
  @UiField HashHexViewer blockHashViewer;

  private final ScheduledCommand defferedHash = new ScheduledCommand() {
    @Override
    public void execute() {
      doHashCycle();
    }
  };

  private final RepeatingCommand executeHashCommand = new RepeatingCommand() {
    @Override
    public boolean execute() {
      doHashCycle();

      if(cancel) {
        cancelled = true;
      }

      return !cancel;
    }
  };

  private RawBlockContainer rawBlock;

  private Presenter presenter;

  private boolean cancel = true;
  private boolean cancelled = true;

  private boolean keepUpWithTip;

  public MineViewImpl() {
    versionViewer = new ValueViewer(BlockPartColorPicker.getFieldColor(BlockPartType.VERSION));
    previousBlockHashViewer = new ValueViewer(BlockPartColorPicker.getFieldColor(BlockPartType.PREV_BLOCK_HASH));
    merkleRootViewer = new ValueViewer(BlockPartColorPicker.getFieldColor(BlockPartType.MERKLE_ROOT));
    timestampViewer = new ValueViewer(BlockPartColorPicker.getFieldColor(BlockPartType.TIMESTAMP));
    bitsViewer = new HashViewer(BlockPartColorPicker.getFieldColor(BlockPartType.BITS));
    nonceViewer = new ValueViewer(BlockPartColorPicker.getFieldColor(BlockPartType.NONCE));

    initWidget(UI_BINDER.createAndBindUi(this));
  }

  @Override
  public void setInformation(final Block initialBlock, final RawBlockContainer rawBlock, final boolean keepUpWithTip) {
    this.rawBlock = rawBlock;
    this.keepUpWithTip = keepUpWithTip;

    versionViewer.setValue(initialBlock.getVersion());
    previousBlockHashViewer.setValue(Str.toString(Hex.encode(initialBlock.getPreviousBlockHash())).toUpperCase());
    merkleRootViewer.setValue(Str.toString(Hex.encode(initialBlock.getMerkleRoot())).toUpperCase());
    timestampViewer.setValue(FormatUtil.formatDateTime(initialBlock.getTimestamp()));
    bitsViewer.setValue(initialBlock.getBits());
    nonceViewer.setValue(initialBlock.getNonce());

    final RawBlockContainer viewBlock = rawBlock.copy();

    // Set up viewBlock for display in hex
    blockHexViewer.setBlock(viewBlock);
    blockHashViewer.setHash(initialBlock.getBlockHash());

    // If we need to stay fly with the latest on the hood, set up the timers
    if(keepUpWithTip) {
      startHashExecutor();
      presenter.startPoll();
    }
  }

  private void doSingleCycle() {
    Scheduler.get().scheduleDeferred(defferedHash);
  }

  private void startHashExecutor() {
    // If we've indicated to cancel, and we haven't yet cancelled, flip the cancel switch
    if(cancel && !cancelled) {
      cancel = false;
      return;
    }

    // If we haven't indicated to cancel, and we haven't actually cancelled, get the hell out
    if(!cancel && !cancelled) {
      return;
    }

    // Otherwise, reset the cancel values and start
    cancel = false;
    cancelled = false;
    Scheduler.get().scheduleFixedPeriod(executeHashCommand, MINING_SIMULATION_DELAY);
  }

  private void cancelHashExecutor() {
    cancel = true;
  }

  @UiHandler("cancelButton")
  public void onCancelClick(final ClickEvent e) {
    cancelHashExecutor();
    if(keepUpWithTip) {
      presenter.pausePoll();
    }
  }

  @UiHandler("continueButton")
  public void onContinueClick(final ClickEvent e) {
    startHashExecutor();
    if(keepUpWithTip) {
      presenter.startPoll();
    }
  }

  @UiHandler("singleCycleButton")
  public void onSingleCycleClick(final ClickEvent e) {
    doSingleCycle();
  }

  @Override
  public void broadcastLatestBlock(final String latestBlock) {
    rawBlock.setPreviousBlockHash(Hex.decode(latestBlock));

    final byte[] previousBlockHash = BlockEncodeUtil.encodePreviousBlockHash(Hex.decode(latestBlock));
    previousBlockHashViewer.setValue(previousBlockHash);
  }

  @Override
  public void setPresenter(final Presenter presenter) {
    this.presenter = presenter;
  }

  private void incrementNonce() {
    final long nonce = NumberParseUtil.parseUint32(rawBlock.getNonce()) + 1;
    nonceViewer.setValue(nonce);
    rawBlock.setNonce(BlockEncodeUtil.encodeNonce(nonce));
  }

  private void doHashCycle() {
    incrementNonce();

    final Date time = new Date();
    timestampViewer.setValue(FormatUtil.formatDateTime(time));
    rawBlock.setTimestamp(BlockEncodeUtil.encodeTimestamp(time));

    blockHexViewer.updateBlock(rawBlock);

    final byte[] computeDoubleSHA256 = ComputeUtil.computeDoubleSHA256(rawBlock.values());
    ArrayUtil.reverse(computeDoubleSHA256);

    blockHashViewer.updateHash(computeDoubleSHA256);
  }
}