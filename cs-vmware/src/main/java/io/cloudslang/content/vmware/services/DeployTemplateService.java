package io.cloudslang.content.vmware.services;

import com.vmware.vim25.HttpNfcLeaseDeviceUrl;
import com.vmware.vim25.HttpNfcLeaseInfo;
import com.vmware.vim25.InvalidPropertyFaultMsg;
import com.vmware.vim25.KeyValue;
import com.vmware.vim25.LocalizedMethodFault;
import com.vmware.vim25.ManagedObjectReference;
import com.vmware.vim25.OvfCreateImportSpecParams;
import com.vmware.vim25.OvfCreateImportSpecResult;
import com.vmware.vim25.OvfFileItem;
import com.vmware.vim25.OvfNetworkMapping;
import com.vmware.vim25.RuntimeFaultFaultMsg;
import com.vmware.vim25.ServiceContent;
import com.vmware.vim25.VimPortType;
import io.cloudslang.content.utils.StringUtilities;
import io.cloudslang.content.vmware.connection.ConnectionResources;
import io.cloudslang.content.vmware.entities.AsyncProgressUpdater;
import io.cloudslang.content.vmware.entities.ClusterParameter;
import io.cloudslang.content.vmware.entities.CustomExecutor;
import io.cloudslang.content.vmware.entities.ITransferVmdkFrom;
import io.cloudslang.content.vmware.entities.ManagedObject;
import io.cloudslang.content.vmware.entities.ProgressUpdater;
import io.cloudslang.content.vmware.entities.SyncProgressUpdater;
import io.cloudslang.content.vmware.entities.TransferVmdkFromFile;
import io.cloudslang.content.vmware.entities.TransferVmdkFromInputStream;
import io.cloudslang.content.vmware.entities.TransferVmdkTask;
import io.cloudslang.content.vmware.entities.TransferVmdkToUrl;
import io.cloudslang.content.vmware.entities.VmInputs;
import io.cloudslang.content.vmware.entities.http.HttpInputs;
import io.cloudslang.content.vmware.services.helpers.MorObjectHandler;
import io.cloudslang.content.vmware.services.utils.VmUtils;
import io.cloudslang.content.vmware.utils.OvfUtils;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.jetbrains.annotations.NotNull;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.cloudslang.content.vmware.constants.Constants.DISK_DRIVE_CIM_TYPE;
import static io.cloudslang.content.vmware.utils.OvfUtils.getHttpNfcLeaseErrorState;
import static io.cloudslang.content.vmware.utils.OvfUtils.getHttpNfcLeaseInfo;
import static io.cloudslang.content.vmware.utils.OvfUtils.getHttpNfcLeaseState;
import static io.cloudslang.content.vmware.utils.OvfUtils.isOva;
import static io.cloudslang.content.vmware.utils.OvfUtils.isOvf;
import static java.lang.Thread.sleep;
import static java.nio.charset.StandardCharsets.UTF_8;

public class DeployTemplateService {

    private final CustomExecutor executor;

    public DeployTemplateService(final boolean parallel) {
        this.executor = new CustomExecutor(parallel);
    }

    public void deployTemplate(final HttpInputs httpInputs, final VmInputs vmInputs, final String templatePath,
                               final Map<String, String> ovfNetworkMap, final Map<String, String> ovfPropertyMap) throws Exception {
        final ConnectionResources connectionResources = new ConnectionResources(httpInputs, vmInputs);
        final ImmutablePair<ManagedObjectReference, OvfCreateImportSpecResult> pair =
                createLeaseSetup(connectionResources, vmInputs, templatePath, ovfNetworkMap, ovfPropertyMap);
        final ManagedObjectReference httpNfcLease = pair.left;
        final OvfCreateImportSpecResult importSpecResult = pair.right;

        final HttpNfcLeaseInfo httpNfcLeaseInfo = getHttpNfcLeaseInfoWhenReady(connectionResources, httpNfcLease);
        final List<HttpNfcLeaseDeviceUrl> deviceUrls = httpNfcLeaseInfo.getDeviceUrl();
        final ProgressUpdater progressUpdater = executor.isParallel() ? new AsyncProgressUpdater(getDisksTotalNoBytes(importSpecResult), httpNfcLease, connectionResources)
                : new SyncProgressUpdater(getDisksTotalNoBytes(importSpecResult), httpNfcLease, connectionResources);

        executor.execute(progressUpdater);
        transferVmdkFiles(templatePath, importSpecResult, deviceUrls, progressUpdater);
        executor.shutdown();
    }

    private ImmutablePair<ManagedObjectReference, OvfCreateImportSpecResult> createLeaseSetup(
            final ConnectionResources connectionResources, final VmInputs vmInputs, final String templatePath,
            final Map<String, String> ovfNetworkMap, final Map<String, String> ovfPropertyMap) throws Exception {
        final ManagedObjectReference ovfManager = getOvfManager(connectionResources);
        final VmUtils vmUtils = new VmUtils();
        final ManagedObjectReference resourcePool;
        if (StringUtilities.isBlank(vmInputs.getClusterName())) {
            resourcePool = vmUtils.getMorResourcePool(vmInputs.getResourcePool(), connectionResources);
        } else {
            ManagedObjectReference clusterMor = new MorObjectHandler().getSpecificMor(connectionResources, connectionResources.getMorRootFolder(),
                    ClusterParameter.CLUSTER_COMPUTE_RESOURCE.getValue(), vmInputs.getClusterName());
            resourcePool = vmUtils.getMorResourcePoolFromCluster(connectionResources, clusterMor, vmInputs.getResourcePool());
        }
        final ManagedObjectReference hostMor = vmUtils.getMorHost(vmInputs.getHostname(), connectionResources, null);
        final ManagedObjectReference datastoreMor = vmUtils.getMorDataStore(vmInputs.getDataStore(), connectionResources, null, vmInputs);
        final ManagedObjectReference folderMor = vmUtils.getMorFolder(vmInputs.getFolderName(), connectionResources);
        final List<OvfNetworkMapping> ovfNetworkMappings = getOvfNetworkMappings(ovfNetworkMap, connectionResources);
        final List<KeyValue> ovfPropertyMappings = getOvfPropertyMappings(ovfPropertyMap);

        final OvfCreateImportSpecResult importSpecResult = connectionResources.getVimPortType().
                createImportSpec(ovfManager, getOvfTemplateAsString(templatePath), resourcePool, datastoreMor, getOvfCreateImportSpecParams(vmInputs, hostMor, ovfNetworkMappings));

        checkImportSpecResultForErrors(importSpecResult);

        final ManagedObjectReference httpNfcLease = OvfUtils.getHttpNfcLease(connectionResources, importSpecResult.getImportSpec(), resourcePool, hostMor, folderMor);
        return ImmutablePair.of(httpNfcLease, importSpecResult);
    }

    private List<KeyValue> getOvfPropertyMappings(Map<String, String> ovfPropertyMap) {
        return null;//TODO
    }

    private List<OvfNetworkMapping> getOvfNetworkMappings(Map<String, String> ovfNetworkMap, ConnectionResources connectionResources) throws InvalidPropertyFaultMsg, RuntimeFaultFaultMsg {
        List<OvfNetworkMapping> mappings = new ArrayList<>();
        for (Map.Entry<String, String> entry : ovfNetworkMap.entrySet()) {
            OvfNetworkMapping mapping = new OvfNetworkMapping();
            mapping.setNetwork(new MorObjectHandler().getSpecificMor(connectionResources, connectionResources.getMorRootFolder(),
                    ManagedObject.NETWORK.getName(), entry.getValue()));
            mapping.setName(entry.getKey());
            mappings.add(mapping);
        }
        return mappings;
    }

    private void checkImportSpecResultForErrors(OvfCreateImportSpecResult importSpecResult) throws Exception {
        if (0 < importSpecResult.getError().size()) {
            StringBuilder stringBuilder = new StringBuilder();
            for (LocalizedMethodFault fault : importSpecResult.getError()) {
                stringBuilder.append(fault.getLocalizedMessage()).append(System.lineSeparator());
            }
            throw new Exception(stringBuilder.toString());
        }
    }

    private void transferVmdkFiles(final String ovfPath, final OvfCreateImportSpecResult importSpecResult,
                                   final List<HttpNfcLeaseDeviceUrl> deviceUrls, final ProgressUpdater progressUpdater) throws Exception {
        for (HttpNfcLeaseDeviceUrl deviceUrl : deviceUrls) {
            final String deviceKey = deviceUrl.getImportKey();
            for (OvfFileItem fileItem : importSpecResult.getFileItem()) {
                if (deviceKey.equals(fileItem.getDeviceId())) {
                    final TransferVmdkTask transferVmdkTask = getTransferVmdkTask(ovfPath, progressUpdater, deviceUrl, fileItem);
                    executor.execute(transferVmdkTask);
                    break;
                }
            }
        }
    }

    @NotNull
    private TransferVmdkTask getTransferVmdkTask(final String ovfPath, final ProgressUpdater progressUpdater, final HttpNfcLeaseDeviceUrl deviceUrl, final OvfFileItem fileItem) throws Exception {
        final URL vmDiskUrl = new URL(deviceUrl.getUrl());
        final ITransferVmdkFrom transferVmdkFrom = getTransferVmdK(ovfPath, fileItem.getPath());
        final TransferVmdkToUrl toUrl = new TransferVmdkToUrl(vmDiskUrl, fileItem.isCreate());

        return new TransferVmdkTask(transferVmdkFrom, toUrl, progressUpdater);
    }

    private ManagedObjectReference getOvfManager(final ConnectionResources connectionResources) throws RuntimeFaultFaultMsg {
        final VimPortType vimPort = connectionResources.getVimPortType();
        final ManagedObjectReference serviceInstance = connectionResources.getServiceInstance();
        final ServiceContent serviceContent = vimPort.retrieveServiceContent(serviceInstance);
        return serviceContent.getOvfManager();
    }

    private ITransferVmdkFrom getTransferVmdK(final String templateFilePathStr, final String vmdkName) throws IOException {
        final Path templateFilePath = Paths.get(templateFilePathStr);
        if (isOva(templateFilePath)) {
            final TarArchiveInputStream tar = new TarArchiveInputStream(new FileInputStream(templateFilePathStr));
            TarArchiveEntry entry;
            while ((entry = tar.getNextTarEntry()) != null) {
                if (entry.getName().startsWith(vmdkName)) {
                    return new TransferVmdkFromInputStream(tar, entry.getSize());
                }
            }
        } else if (isOvf(templateFilePath)) {
            final Path vmdkPath = templateFilePath.getParent().resolve(vmdkName);
            return new TransferVmdkFromFile(vmdkPath.toFile());
        }
        throw new RuntimeException("Template file is not ova or ovf!");
    }

    @NotNull
    private HttpNfcLeaseInfo getHttpNfcLeaseInfoWhenReady(final ConnectionResources connectionResources, final ManagedObjectReference httpNfcLease) throws Exception {
        String leaseState = getHttpNfcLeaseState(connectionResources, httpNfcLease);
        while (!"ready".equals(leaseState)) {
            leaseState = getHttpNfcLeaseState(connectionResources, httpNfcLease);
            if ("error".equals(leaseState)) {
                throw new RuntimeException("Failed to get a HTTP NFC Lease: " + getHttpNfcLeaseErrorState(connectionResources, httpNfcLease));
            }
            sleep(100);
        }
        return getHttpNfcLeaseInfo(connectionResources, httpNfcLease);
    }

    @NotNull
    private String getOvfTemplateAsString(final String templatePath) throws IOException {
        if (isOva(Paths.get(templatePath))) {
            try (final TarArchiveInputStream tar = new TarArchiveInputStream(new FileInputStream(templatePath))) {
                TarArchiveEntry entry;
                while ((entry = tar.getNextTarEntry()) != null) {
                    if (isOvf(Paths.get(entry.getName()))) {
                        return OvfUtils.writeToString(tar, entry.getSize());
                    }
                }
            }
        } else if (isOvf(Paths.get(templatePath))) {
            final InputStream inputStream = new FileInputStream(templatePath);
            return IOUtils.toString(inputStream, UTF_8);
        }
        throw new RuntimeException("Template file could not be read!");
    }


    public OvfCreateImportSpecParams getOvfCreateImportSpecParams(final VmInputs vmInputs, final ManagedObjectReference hostSystem, List ovfNetworkMappings) {
        final OvfCreateImportSpecParams params = new OvfCreateImportSpecParams();
        params.setHostSystem(hostSystem);
        params.setDeploymentOption("");
        params.setLocale(String.valueOf(vmInputs.getLocale()));
        params.setEntityName(vmInputs.getVirtualMachineName());
        params.setIpAllocationPolicy(vmInputs.getIpAllocScheme());
        params.setIpProtocol(vmInputs.getIpProtocol());
        params.setDiskProvisioning(vmInputs.getDiskProvisioning());
        params.getNetworkMapping().addAll(ovfNetworkMappings);
        return params;
    }

    public long getDisksTotalNoBytes(final OvfCreateImportSpecResult importSpecResult) {
        long disksTotalNoBytes = 0;
        for (final OvfFileItem item : importSpecResult.getFileItem()) {
            if (item.getCimType() == DISK_DRIVE_CIM_TYPE) {
                disksTotalNoBytes += item.getSize();
            }
        }
        return disksTotalNoBytes;
    }
}