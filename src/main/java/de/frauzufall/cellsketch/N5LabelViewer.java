/*-
 * #%L
 * cellsketch
 * %%
 * Copyright (C) 2020 - 2023 Deborah Schmidt
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */
package de.frauzufall.cellsketch;

import bdv.BigDataViewer;
import bdv.tools.brightness.ConverterSetup;
import bdv.tools.transformation.TransformedSource;
import bdv.ui.splitpanel.SplitPanel;
import bdv.util.*;
import bdv.util.volatiles.VolatileTypeMatcher;
import bdv.util.volatiles.VolatileViews;
import bdv.viewer.Source;
import bdv.viewer.SourceAndConverter;
import bdv.viewer.ViewerFrame;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.Volatile;
import net.imglib2.cache.img.CachedCellImg;
import net.imglib2.converter.Converter;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.volatiles.VolatileARGBType;
import net.imglib2.util.Util;
import net.imglib2.view.Views;
import net.miginfocom.swing.MigLayout;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.bdv.MultiscaleDatasets;
import org.janelia.saalfeldlab.n5.bdv.N5Source;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.janelia.saalfeldlab.n5.metadata.*;
import org.janelia.saalfeldlab.n5.metadata.canonical.CanonicalMultichannelMetadata;
import org.janelia.saalfeldlab.n5.metadata.canonical.CanonicalMultiscaleMetadata;
import org.janelia.saalfeldlab.n5.metadata.canonical.CanonicalSpatialMetadata;
import org.janelia.saalfeldlab.n5.ui.DataSelection;
import org.scijava.Context;
import org.scijava.app.StatusService;
import org.scijava.app.event.StatusEvent;
import org.scijava.event.EventHandler;
import org.scijava.plugin.Parameter;
import org.scijava.ui.DialogPrompt;
import org.scijava.ui.StatusBar;
import org.scijava.ui.UIService;
import sc.fiji.labeleditor.plugin.interfaces.bdv.BdvInterface;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static bdv.BigDataViewer.createConverterToARGB;
import static bdv.BigDataViewer.wrapWithTransformedSource;


/**
 *
 * This class is partly copied from the N5Viewer by Igor Pisarev and John Bogovic
 */
public class N5LabelViewer {

	private final List<BdvSource> rawSources;
	private int numTimepoints = 1;

	private boolean is2D = true;

	private final BdvHandle bdv;

	public BdvHandle getBdv() {
		return bdv;
	}

	/**
	 * Creates a new N5Viewer with the given data sets.
	 * @param dataSelection data sets to display
	 * @param labelEditorInterface
	 * @param project
	 * @throws IOException
	 */
	public < T extends NumericType< T > & NativeType< T >,
					V extends Volatile< T > & NumericType< V >,
					R extends N5Reader >
	N5LabelViewer(final DataSelection dataSelection, final BdvInterface labelEditorInterface, final DefaultBdvProject project) throws IOException
	{
		Prefs.showScaleBar( true );

		// TODO: These setups are not used anymore, because BdvFunctions creates its own.
		//       They either need to be deleted from here or integrated somehow.
		final List< ConverterSetup > converterSetups = new ArrayList<>();

		final List< SourceAndConverter< T > > sourcesAndConverters = new ArrayList<>();

		final List<N5Metadata> selected = new ArrayList<>();
		for( final N5Metadata meta : dataSelection.metadata )
		{
			if( meta instanceof N5ViewerMultichannelMetadata )
			{
				final N5ViewerMultichannelMetadata mc = (N5ViewerMultichannelMetadata)meta;
				for( final MultiscaleMetadata<?> m : mc.getChildrenMetadata() )
					selected.add( m );
			}
			else if ( meta instanceof CanonicalMultichannelMetadata )
			{
				final CanonicalMultichannelMetadata mc = (CanonicalMultichannelMetadata)meta;
				for( final N5Metadata m : mc.getChildrenMetadata() )
					selected.add( m );
			}
			else
				selected.add( meta );
		}

		final List<N5Source<T>> sources = new ArrayList<>();
		final List<N5Source<V>> volatileSources = new ArrayList<>();

		buildN5Sources(dataSelection.n5, selected, converterSetups, sourcesAndConverters, sources, volatileSources);

		BdvHandle bdvHandle = null;

		BdvOptions options = BdvOptions.options().frameTitle(CellProject.appName).accumulateProjectorFactory(labelEditorInterface.projector());
		if (is2D) {
			options = options.is2D();
		}

		List<BdvSource> bdvSources = new ArrayList<>();
		for (final SourceAndConverter<?> sourcesAndConverter : sourcesAndConverters) {
			if (bdvHandle == null) {
				// Create and show a BdvHandleFrame with the first source
				BdvStackSource<?> source = BdvFunctions.show(sourcesAndConverter, options);
				ViewerFrame frame = ((BdvHandleFrame)source.getBdvHandle()).getBigDataViewer().getViewerFrame();
				frame.addWindowListener(new WindowAdapter() {
					@Override
					public void windowClosed(WindowEvent e) {
						project.dispose();
					}
				});
				bdvSources.add(source);
				bdvHandle = source.getBdvHandle();
				labelEditorInterface.setup(bdvHandle);
				frame.getCardPanel().getComponent().setLayout(new MigLayout("fillx", "[grow]"));
				frame.getCardPanel().getComponent().setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
				SplitPanel splitPanel = frame.getSplitPanel();
				frame.getContentPane().remove(splitPanel);
				JPanel newPanel = new JPanel(new MigLayout("fillx"));
				frame.getContentPane().add(newPanel);
				newPanel.add(splitPanel, "wrap, push, span, grow");
				newPanel.add(createStatusBar(project.context()), "h 30");
				frame.pack();
				frame.getSplitPanel().setCollapsed( true );
				project.context().service(StatusService.class).showStatus("N5 BigDataViewer initialized");
			}
			else {
				// Subsequent sources are added to the existing handle
				bdvSources.add(BdvFunctions.show(sourcesAndConverter, BdvOptions.options().addTo(bdvHandle)));
			}
		}
		this.bdv = bdvHandle;
		this.rawSources = bdvSources;
	}

	private Component createStatusBar(Context context) {
		SwingStatusBar statusBar = new SwingStatusBar(context);
		return statusBar;
	}

	public < T extends NumericType< T > & NativeType< T >,
				V extends Volatile< T > & NumericType< V >,
				R extends N5Reader >
	List<BdvSource> addData( final DataSelection selection ) throws IOException
	{
		final ArrayList< ConverterSetup > converterSetups = new ArrayList<>();
		final ArrayList< SourceAndConverter< T > > sourcesAndConverters = new ArrayList<>();

		final List<N5Metadata> selected = new ArrayList<>();
		for( final N5Metadata meta : selection.metadata )
		{
			if( meta instanceof N5ViewerMultichannelMetadata )
			{
				final N5ViewerMultichannelMetadata mc = (N5ViewerMultichannelMetadata)meta;
				selected.addAll(Arrays.asList(mc.getChildrenMetadata()));
			}
			else if ( meta instanceof CanonicalMultichannelMetadata )
			{
				final CanonicalMultichannelMetadata mc = (CanonicalMultichannelMetadata)meta;
				selected.addAll(Arrays.asList(mc.getChildrenMetadata()));
			}
			else
				selected.add( meta );
		}

		final List<N5Source<T>> sources = new ArrayList<>();
		final List<N5Source<V>> volatileSources = new ArrayList<>();

		buildN5Sources(selection.n5, selected, converterSetups, sourcesAndConverters, sources, volatileSources);

		final List<BdvSource> bdvSources = new ArrayList<>();
		for (final SourceAndConverter<?> sourcesAndConverter : sourcesAndConverters) {
			bdvSources.add(BdvFunctions.show(sourcesAndConverter, BdvOptions.options().addTo(bdv)));
		}

		return bdvSources;
	}

	public < T extends NumericType< T > & NativeType< T >,
					V extends Volatile< T > & NumericType< V >> void buildN5Sources(
		final N5Reader n5,
		final List<N5Metadata> selectedMetadata,
		final List< ConverterSetup > converterSetups,
		final List< SourceAndConverter< T > > sourcesAndConverters,
		final List<N5Source<T>> sources,
		final List<N5Source<V>> volatileSources) throws IOException
	{
		final ArrayList<MetadataSource<?>> additionalSources = new ArrayList<>();

		int i;
		for ( i = 0; i < selectedMetadata.size(); ++i )
		{
			String[] datasetsToOpen = null;
			AffineTransform3D[] transforms = null;

			final N5Metadata metadata = selectedMetadata.get( i );
			final String srcName = metadata.getName();
			if (metadata instanceof N5SingleScaleMetadata) {
				final N5SingleScaleMetadata singleScaleDataset = (N5SingleScaleMetadata) metadata;
				final String[] tmpDatasets= new String[]{ singleScaleDataset.getPath() };
				final AffineTransform3D[] tmpTransforms = new AffineTransform3D[]{ singleScaleDataset.spatialTransform3d() };

				final MultiscaleDatasets msd = MultiscaleDatasets.sort( tmpDatasets, tmpTransforms );
				datasetsToOpen = msd.getPaths();
				transforms = msd.getTransforms();
			} else if (metadata instanceof N5MultiScaleMetadata) {
				final N5MultiScaleMetadata multiScaleDataset = (N5MultiScaleMetadata) metadata;
				datasetsToOpen = multiScaleDataset.getPaths();
				transforms = multiScaleDataset.spatialTransforms3d();
			} else if (metadata instanceof N5CosemMetadata ) {
				final N5CosemMetadata singleScaleCosemDataset = (N5CosemMetadata) metadata;
				datasetsToOpen = new String[]{ singleScaleCosemDataset.getPath() };
				transforms = new AffineTransform3D[]{ singleScaleCosemDataset.spatialTransform3d() };
			} else if (metadata instanceof CanonicalSpatialMetadata ) {
				final CanonicalSpatialMetadata canonicalDataset = (CanonicalSpatialMetadata) metadata;
				datasetsToOpen = new String[]{ canonicalDataset.getPath() };
				transforms = new AffineTransform3D[]{ canonicalDataset.getSpatialTransform().spatialTransform3d() };
			} else if (metadata instanceof N5CosemMultiScaleMetadata ) {
				final N5CosemMultiScaleMetadata multiScaleDataset = (N5CosemMultiScaleMetadata) metadata;
				final MultiscaleDatasets msd = MultiscaleDatasets.sort( multiScaleDataset.getPaths(), multiScaleDataset.spatialTransforms3d() );
				datasetsToOpen = msd.getPaths();
				transforms = msd.getTransforms();
			} else if (metadata instanceof CanonicalMultiscaleMetadata ) {
				final CanonicalMultiscaleMetadata multiScaleDataset = (CanonicalMultiscaleMetadata) metadata;
				final MultiscaleDatasets msd = MultiscaleDatasets.sort( multiScaleDataset.getPaths(), multiScaleDataset.spatialTransforms3d() );
				datasetsToOpen = msd.getPaths();
				transforms = msd.getTransforms();
			}
			else if( metadata instanceof N5DatasetMetadata ) {
				final List<MetadataSource<?>> addTheseSources = MetadataSource.buildMetadataSources(n5, (N5DatasetMetadata)metadata);
				if( addTheseSources != null )
					additionalSources.addAll(addTheseSources);
			}
			else {
				datasetsToOpen = new String[]{ metadata.getPath() };
				transforms = new AffineTransform3D[] { new AffineTransform3D() };
			}

			if( datasetsToOpen == null || datasetsToOpen.length == 0 )
				continue;

			// is2D should be true at the end of this loop if all sources are 2D
			is2D = true;

			@SuppressWarnings( "rawtypes" )
			final RandomAccessibleInterval[] images = new RandomAccessibleInterval[datasetsToOpen.length];
			for ( int s = 0; s < images.length; ++s )
			{
				final CachedCellImg<?, ?> vimg = N5Utils.openVolatile( n5, datasetsToOpen[s] );
				if( vimg.numDimensions() == 2 )
				{
					images[ s ] = Views.addDimension(vimg, 0, 0);
					is2D = is2D && true;
				}
				else
				{
					images[ s ] = vimg;
					is2D = is2D && false;
				}
			}

			final RandomAccessibleInterval[] vimages = new RandomAccessibleInterval[images.length];
			for (int s = 0; s < images.length; ++s) {
				vimages[s] = VolatileViews.wrapAsVolatile(images[s]);
			}
			// TODO: Ideally, the volatile views should use a caching strategy
			//   where blocks are enqueued with reverse resolution level as
			//   priority. However, this would require to predetermine the number
			//   of resolution levels, which would man a lot of duplicated code
			//   for analyzing selectedMetadata. Instead, wait until SharedQueue
			//   supports growing numPriorities, then revisit.
			//   See https://github.com/imglib/imglib2-cache/issues/18.
			//   Probably it should look like this:
//			sharedQueue.ensureNumPriorities(images.length);
//			for (int s = 0; s < images.length; ++s) {
//				final int priority = images.length - 1 - s;
//				final CacheHints cacheHints = new CacheHints(LoadingStrategy.BUDGETED, priority, false);
//				vimages[s] = VolatileViews.wrapAsVolatile(images[s], sharedQueue, cacheHints);
//			}

			@SuppressWarnings("unchecked")
			final T type = (T) Util.getTypeFromInterval(images[0]);
			final N5Source<T> source = new N5Source<>(
					type,
					srcName,
					images,
					transforms);

			@SuppressWarnings("unchecked")
			final V volatileType = (V) VolatileTypeMatcher.getVolatileTypeForType(type);
			final N5Source<V> volatileSource = new N5Source<>(
					volatileType,
					srcName,
					vimages,
					transforms);

			sources.add(source);
			volatileSources.add(volatileSource);

			addSourceToListsGenericType(source, volatileSource, i + 1, converterSetups, sourcesAndConverters);
		}

		for( final MetadataSource src : additionalSources ) {
			if( src.numTimePoints() > numTimepoints )
				numTimepoints = src.numTimePoints();

			addSourceToListsGenericType( src, i + 1, converterSetups, sourcesAndConverters );
		}
	}

	/**
	 * Add the given {@code source} to the lists of {@code converterSetups}
	 * (using specified {@code setupId}) and {@code sources}. For this, the
	 * {@code source} is wrapped with an appropriate {@link Converter} to
	 * {@link ARGBType} and into a {@link TransformedSource}.
	 *
	 * @param source
	 *            source to add.
	 * @param setupId
	 *            id of the new source for use in {@code SetupAssignments}.
	 * @param converterSetups
	 *            list of {@link ConverterSetup}s to which the source should be
	 *            added.
	 * @param sources
	 *            list of {@link SourceAndConverter}s to which the source should
	 *            be added.
	 */
	@SuppressWarnings( { "rawtypes", "unchecked" } )
	private static < T > void addSourceToListsGenericType(
			final Source< T > source,
			final int setupId,
			final List< ConverterSetup > converterSetups,
			final List< SourceAndConverter< T > > sources )
	{
		addSourceToListsGenericType( source, null, setupId, converterSetups, sources );
	}

	/**
	 * Add the given {@code source} to the lists of {@code converterSetups}
	 * (using specified {@code setupId}) and {@code sources}. For this, the
	 * {@code source} is wrapped with an appropriate {@link Converter} to
	 * {@link ARGBType} and into a {@link TransformedSource}.
	 *
	 * @param source
	 *            source to add.
	 * @param setupId
	 *            id of the new source for use in {@code SetupAssignments}.
	 * @param converterSetups
	 *            list of {@link ConverterSetup}s to which the source should be
	 *            added.
	 * @param sources
	 *            list of {@link SourceAndConverter}s to which the source should
	 *            be added.
	 */
	@SuppressWarnings( { "rawtypes", "unchecked" } )
	private static < T, V extends Volatile< T > > void addSourceToListsGenericType(
			final Source< T > source,
			final Source< V > volatileSource,
			final int setupId,
			final List< ConverterSetup > converterSetups,
			final List< SourceAndConverter< T > > sources )
	{
		final T type = source.getType();
		if ( type instanceof RealType || type instanceof ARGBType || type instanceof VolatileARGBType )
			addSourceToListsNumericType( ( Source ) source, ( Source ) volatileSource, setupId, converterSetups, ( List ) sources );
		else
			throw new IllegalArgumentException( "Unknown source type. Expected RealType, ARGBType, or VolatileARGBType" );
	}

	/**
	 * Add the given {@code source} to the lists of {@code converterSetups}
	 * (using specified {@code setupId}) and {@code sources}. For this, the
	 * {@code source} is wrapped with an appropriate {@link Converter} to
	 * {@link ARGBType} and into a {@link TransformedSource}.
	 *
	 * @param source
	 *            source to add.
	 * @param volatileSource
	 *            corresponding volatile source.
	 * @param setupId
	 *            id of the new source for use in {@code SetupAssignments}.
	 * @param converterSetups
	 *            list of {@link ConverterSetup}s to which the source should be
	 *            added.
	 * @param sources
	 *            list of {@link SourceAndConverter}s to which the source should
	 *            be added.
	 */
	private static < T extends NumericType< T >, V extends Volatile< T > & NumericType< V > > void addSourceToListsNumericType(
			final Source< T > source,
			final Source< V > volatileSource,
			final int setupId,
			final List< ConverterSetup > converterSetups,
			final List< SourceAndConverter< T > > sources )
	{
		final SourceAndConverter< V > vsoc = ( volatileSource == null )
				? null
				: new SourceAndConverter<>( volatileSource, createConverterToARGB( volatileSource.getType() ) );
		final SourceAndConverter< T > soc = new SourceAndConverter<>( source, createConverterToARGB( source.getType() ), vsoc );
		final SourceAndConverter< T > tsoc = wrapWithTransformedSource( soc );

		converterSetups.add( BigDataViewer.createConverterSetup( tsoc, setupId ) );
		sources.add( tsoc );
	}

	public List<BdvSource> getSourceSources() {
		return rawSources;
	}

	public class SwingStatusBar extends JPanel implements StatusBar {
		private final JLabel statusText;
		private final JProgressBar progressBar;

		@Parameter
		private UIService uiService;

		public SwingStatusBar(Context context) {
			context.inject(this);
			this.statusText = new JLabel();

			this.progressBar = new JProgressBar();
			this.progressBar.setVisible(false);
			this.setLayout(new MigLayout("hidemode 2", "[][shrink]push"));
			this.add(this.progressBar, "w 100");
			this.add(this.statusText, "shrink");
		}

		public void setStatus(String message) {
			if (message != null) {
				String text;
				if (message.isEmpty()) {
					text = " ";
				} else {
					text = message;
				}

				this.statusText.setText(truncateLongWords(text));
				this.statusText.setToolTipText(text);
			}
		}

		private String truncateLongWords(String text) {
			StringBuilder res = new StringBuilder();
			String[] parts = text.split(" ");
			int maxlen = 50;
			for(String part : parts) {
				if(part.length() > maxlen) {
					part = part.substring(0, maxlen) + "[..]";
				}
				res.append(part + " ");
			}
			return res.toString();
		}

		public void setProgress(int val, int max) {
			if (max < 0) {
				this.progressBar.setVisible(false);
			} else {
				if (val >= 0 && val < max) {
					this.progressBar.setValue(val);
					this.progressBar.setMaximum(max);
					this.progressBar.setVisible(true);
				} else {
					this.progressBar.setVisible(false);
				}

			}
		}

		@EventHandler
		protected void onEvent(StatusEvent event) {
			if (event.isWarning()) {
				String message = event.getStatusMessage();
				if (message != null && !message.isEmpty()) {
					this.uiService.showDialog(message, DialogPrompt.MessageType.WARNING_MESSAGE);
				}
			} else {
				int val = event.getProgressValue();
				int max = event.getProgressMaximum();
				String message = this.uiService.getStatusMessage(event);
				this.setStatus(message);
				this.setProgress(val, max);
			}

		}
	}

}
