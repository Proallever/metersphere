package io.metersphere.api.service;


import com.alibaba.fastjson.JSON;
import io.metersphere.api.dto.definition.*;
import io.metersphere.base.domain.*;
import io.metersphere.base.mapper.ApiDefinitionMapper;
import io.metersphere.base.mapper.ApiModuleMapper;
import io.metersphere.base.mapper.ext.ExtApiDefinitionMapper;
import io.metersphere.base.mapper.ext.ExtApiModuleMapper;
import io.metersphere.commons.constants.TestCaseConstants;
import io.metersphere.commons.exception.MSException;
import io.metersphere.commons.utils.LogUtil;
import io.metersphere.commons.utils.SessionUtils;
import io.metersphere.i18n.Translator;
import io.metersphere.log.utils.ReflexObjectUtil;
import io.metersphere.log.vo.DetailColumn;
import io.metersphere.log.vo.OperatingLogDetails;
import io.metersphere.log.vo.api.ModuleReference;
import io.metersphere.service.NodeTreeService;
import io.metersphere.service.ProjectService;
import io.metersphere.track.service.TestPlanApiCaseService;
import io.metersphere.track.service.TestPlanProjectService;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional(rollbackFor = Exception.class)
public class ApiModuleService extends NodeTreeService<ApiModuleDTO> {

    @Resource
    ApiModuleMapper apiModuleMapper;
    @Resource
    ExtApiModuleMapper extApiModuleMapper;
    @Resource
    private ExtApiDefinitionMapper extApiDefinitionMapper;
    @Resource
    private TestPlanProjectService testPlanProjectService;
    @Resource
    private ProjectService projectService;
    @Resource
    private TestPlanApiCaseService testPlanApiCaseService;
    @Resource
    private ApiTestCaseService apiTestCaseService;
    @Resource
    private ApiDefinitionService apiDefinitionService;

    @Resource
    SqlSessionFactory sqlSessionFactory;

    public ApiModuleService() {
        super(ApiModuleDTO.class);
    }

    public ApiModule get(String id) {
        return apiModuleMapper.selectByPrimaryKey(id);
    }


    public List<ApiModuleDTO> getApiModulesByProjectAndPro(String projectId, String protocol) {
        return extApiModuleMapper.getNodeTreeByProjectId(projectId, protocol);
    }

    public List<ApiModuleDTO> getNodeTreeByProjectId(String projectId, String protocol, String versionId) {
        // 判断当前项目下是否有默认模块，没有添加默认模块
        this.getDefaultNode(projectId, protocol);
        ApiDefinitionRequest request = new ApiDefinitionRequest();
        List<ApiModuleDTO> apiModules = getApiModulesByProjectAndPro(projectId, protocol);
        request.setProjectId(projectId);
        request.setProtocol(protocol);
        List<String> list = new ArrayList<>();
        list.add("Prepare");
        list.add("Underway");
        list.add("Completed");
        Map<String, List<String>> filters = new LinkedHashMap<>();
        filters.put("status", list);
        request.setFilters(filters);
//        apiModules.forEach(node -> {
//            List<String> moduleIds = new ArrayList<>();
//            moduleIds = this.nodeList(apiModules, node.getId(), moduleIds);
//            moduleIds.add(node.getId());
//            request.setModuleIds(moduleIds);
//            node.setCaseNum(extApiDefinitionMapper.moduleCount(request));
//        });

        //优化： 所有统计SQL一次查询出来
        List<String> allModuleIdList = new ArrayList<>();
        for (ApiModuleDTO node : apiModules) {
            List<String> moduleIds = new ArrayList<>();
            moduleIds = this.nodeList(apiModules, node.getId(), moduleIds);
            moduleIds.add(node.getId());
            for (String moduleId : moduleIds) {
                if (!allModuleIdList.contains(moduleId)) {
                    allModuleIdList.add(moduleId);
                }
            }
        }
        request.setModuleIds(allModuleIdList);
        if (StringUtils.isNotBlank(versionId)) {
            request.setVersionId(versionId);
        }
        List<Map<String, Object>> moduleCountList = extApiDefinitionMapper.moduleCountByCollection(request);
        Map<String, Integer> moduleCountMap = this.parseModuleCountList(moduleCountList);
        apiModules.forEach(node -> {
            List<String> moduleIds = new ArrayList<>();
            moduleIds = this.nodeList(apiModules, node.getId(), moduleIds);
            moduleIds.add(node.getId());
            int countNum = 0;
            for (String moduleId : moduleIds) {
                if (moduleCountMap.containsKey(moduleId)) {
                    countNum += moduleCountMap.get(moduleId).intValue();
                }
            }
            node.setCaseNum(countNum);
        });
        return getNodeTrees(apiModules);
    }

    private Map<String, Integer> parseModuleCountList(List<Map<String, Object>> moduleCountList) {
        Map<String, Integer> returnMap = new HashMap<>();
        for (Map<String, Object> map : moduleCountList) {
            Object moduleIdObj = map.get("moduleId");
            Object countNumObj = map.get("countNum");
            if (moduleIdObj != null && countNumObj != null) {
                String moduleId = String.valueOf(moduleIdObj);
                try {
                    Integer countNumInteger = new Integer(String.valueOf(countNumObj));
                    returnMap.put(moduleId, countNumInteger);
                } catch (Exception e) {
                    LogUtil.error("method parseModuleCountList has error:", e);
                }
            }
        }
        return returnMap;
    }

    public static List<String> nodeList(List<ApiModuleDTO> apiNodes, String pid, List<String> list) {
        for (ApiModuleDTO node : apiNodes) {
            //遍历出父id等于参数的id，add进子节点集合
            if (StringUtils.equals(node.getParentId(), pid)) {
                list.add(node.getId());
                //递归遍历下一级
                nodeList(apiNodes, node.getId(), list);
            }
        }
        return list;
    }

    public String addNode(ApiModule node) {
        validateNode(node);
        return addNodeWithoutValidate(node);
    }

    public double getNextLevelPos(String projectId, int level, String parentId) {
        List<ApiModule> list = getPos(projectId, level, parentId, "pos desc");
        if (!CollectionUtils.isEmpty(list) && list.get(0) != null && list.get(0).getPos() != null) {
            return list.get(0).getPos() + DEFAULT_POS;
        } else {
            return DEFAULT_POS;
        }
    }

    private List<ApiModule> getPos(String projectId, int level, String parentId, String order) {
        ApiModuleExample example = new ApiModuleExample();
        ApiModuleExample.Criteria criteria = example.createCriteria();
        criteria.andProjectIdEqualTo(projectId).andLevelEqualTo(level);
        if (level != 1 && StringUtils.isNotBlank(parentId)) {
            criteria.andParentIdEqualTo(parentId);
        }
        example.setOrderByClause(order);
        return apiModuleMapper.selectByExample(example);
    }

    public String addNodeWithoutValidate(ApiModule node) {
        node.setCreateTime(System.currentTimeMillis());
        node.setUpdateTime(System.currentTimeMillis());
        node.setId(UUID.randomUUID().toString());
        if (StringUtils.isBlank(node.getCreateUser())) {
            node.setCreateUser(SessionUtils.getUserId());
        }
        double pos = getNextLevelPos(node.getProjectId(), node.getLevel(), node.getParentId());
        node.setPos(pos);
        apiModuleMapper.insertSelective(node);
        return node.getId();
    }

    public List<ApiModuleDTO> getNodeByPlanId(String planId, String protocol) {
        List<ApiModuleDTO> list = new ArrayList<>();
        List<String> projectIds = testPlanProjectService.getProjectIdsByPlanId(planId);
        projectIds.forEach(id -> {
            Project project = projectService.getProjectById(id);
            String name = project.getName();
            List<ApiModuleDTO> nodeList = getNodeDTO(id, planId, protocol);
            ApiModuleDTO apiModuleDTO = new ApiModuleDTO();
            apiModuleDTO.setId(project.getId());
            apiModuleDTO.setName(name);
            apiModuleDTO.setLabel(name);
            apiModuleDTO.setChildren(nodeList);
            if (!org.springframework.util.CollectionUtils.isEmpty(nodeList)) {
                list.add(apiModuleDTO);
            }
        });
        return list;
    }

    private List<ApiModuleDTO> getNodeDTO(String projectId, String planId, String protocol) {
        List<TestPlanApiCase> apiCases = testPlanApiCaseService.getCasesByPlanId(planId);
        if (apiCases.isEmpty()) {
            return null;
        }
        List<ApiModuleDTO> testCaseNodes = getApiModulesByProjectAndPro(projectId, protocol);

        List<String> caseIds = apiCases.stream()
                .map(TestPlanApiCase::getApiCaseId)
                .collect(Collectors.toList());

        List<String> definitionIds = apiTestCaseService.selectCasesBydIds(caseIds).stream()
                .map(ApiTestCase::getApiDefinitionId)
                .collect(Collectors.toList());

        List<String> dataNodeIds = apiDefinitionService.selectApiDefinitionBydIds(definitionIds).stream()
                .map(ApiDefinition::getModuleId)
                .collect(Collectors.toList());

        List<ApiModuleDTO> nodeTrees = getNodeTrees(testCaseNodes);

        Iterator<ApiModuleDTO> iterator = nodeTrees.iterator();
        while (iterator.hasNext()) {
            ApiModuleDTO rootNode = iterator.next();
            if (pruningTree(rootNode, dataNodeIds)) {
                iterator.remove();
            }
        }
        return nodeTrees;
    }


    public ApiModule getNewModule(String name, String projectId, int level) {
        ApiModule node = new ApiModule();
        buildNewModule(node);
        node.setLevel(level);
        node.setName(name);
        node.setProjectId(projectId);
        return node;
    }

    public ApiModule buildNewModule(ApiModule node) {
        node.setCreateTime(System.currentTimeMillis());
        node.setUpdateTime(System.currentTimeMillis());
        node.setId(UUID.randomUUID().toString());
        return node;
    }

    private void validateNode(ApiModule node) {
        if (node.getLevel() > TestCaseConstants.MAX_NODE_DEPTH) {
            MSException.throwException(Translator.get("test_case_node_level_tip")
                    + TestCaseConstants.MAX_NODE_DEPTH + Translator.get("test_case_node_level"));
        }
        checkApiModuleExist(node);
    }

    private void checkApiModuleExist(ApiModule node) {
        if (node.getName() != null) {
            ApiModuleExample example = new ApiModuleExample();
            ApiModuleExample.Criteria criteria = example.createCriteria();
            criteria.andNameEqualTo(node.getName())
                    .andProjectIdEqualTo(node.getProjectId());

            if (StringUtils.isNotBlank(node.getParentId())) {
                criteria.andParentIdEqualTo(node.getParentId());
            } else {
                criteria.andLevelEqualTo(node.getLevel());
            }

            if (StringUtils.isNotBlank(node.getProtocol())) {
                criteria.andProtocolEqualTo(node.getProtocol());
            }

            if (StringUtils.isNotBlank(node.getId())) {
                criteria.andIdNotEqualTo(node.getId());
            }
            if (apiModuleMapper.selectByExample(example).size() > 0) {
                MSException.throwException(Translator.get("test_case_module_already_exists") + ": " + node.getName());
            }
        }
    }

    public List<ApiModule> selectSameModule(ApiModule node) {
        ApiModuleExample example = new ApiModuleExample();
        ApiModuleExample.Criteria criteria = example.createCriteria();
        criteria.andNameEqualTo(node.getName())
                .andProjectIdEqualTo(node.getProjectId())
                .andLevelEqualTo(node.getLevel());

        if (StringUtils.isNotBlank(node.getId())) {
            criteria.andIdNotEqualTo(node.getId());
        }
        if (StringUtils.isNotEmpty(node.getProtocol())) {
            criteria.andProtocolEqualTo(node.getProtocol());
        }
        //同一个模块下不能有相同名字的子模块
        if (StringUtils.isNotBlank(node.getParentId())) {
            criteria.andParentIdEqualTo(node.getParentId());
        }
        return apiModuleMapper.selectByExample(example);
    }

    private List<ApiDefinitionResult> queryByModuleIds(List<String> nodeIds) {
        ApiDefinitionRequest apiDefinitionRequest = new ApiDefinitionRequest();
        apiDefinitionRequest.setModuleIds(nodeIds);
        return extApiDefinitionMapper.list(apiDefinitionRequest);
    }

    public int editNode(DragModuleRequest request) {
        request.setUpdateTime(System.currentTimeMillis());
        checkApiModuleExist(request);
        List<ApiDefinitionResult> apiDefinitionResults = queryByModuleIds(request.getNodeIds());
        if (CollectionUtils.isNotEmpty(apiDefinitionResults)) {
            apiDefinitionResults.forEach(apiDefinition -> {
                if (apiDefinition != null && StringUtils.isNotBlank(apiDefinition.getModulePath())) {
                    StringBuilder path = new StringBuilder(apiDefinition.getModulePath());
                    List<String> pathLists = Arrays.asList(path.toString().split("/"));
                    if (pathLists.size() > request.getLevel()) {
                        pathLists.set(request.getLevel(), request.getName());
                        path.delete(0, path.length());
                        for (int i = 1; i < pathLists.size(); i++) {
                            path = path.append("/").append(pathLists.get(i));
                        }
                        apiDefinition.setModulePath(path.toString());
                    }
                }
            });
            batchUpdateApiDefinition(apiDefinitionResults);
        }
        return apiModuleMapper.updateByPrimaryKeySelective(request);
    }

    public int deleteNode(List<String> nodeIds) {
        ApiDefinitionExampleWithOperation apiDefinitionExample = new ApiDefinitionExampleWithOperation();
        apiDefinitionExample.createCriteria().andModuleIdIn(nodeIds);
        apiDefinitionExample.setOperator(SessionUtils.getUserId());
        apiDefinitionExample.setOperationTime(System.currentTimeMillis());
        apiDefinitionService.removeToGcByExample(apiDefinitionExample);
//        extApiDefinitionMapper.removeToGcByExample(apiDefinitionExample);   //  删除模块，则模块下的接口放入回收站

        ApiModuleExample apiDefinitionNodeExample = new ApiModuleExample();
        apiDefinitionNodeExample.createCriteria().andIdIn(nodeIds);
        return apiModuleMapper.deleteByExample(apiDefinitionNodeExample);
    }

    private void batchUpdateApiDefinition(List<ApiDefinitionResult> apiModule) {
        SqlSession sqlSession = sqlSessionFactory.openSession(ExecutorType.BATCH);
        ApiDefinitionMapper apiDefinitionMapper = sqlSession.getMapper(ApiDefinitionMapper.class);
        apiModule.forEach((value) -> {
            apiDefinitionMapper.updateByPrimaryKeySelective(value);
        });
        sqlSession.flushStatements();
        if (sqlSession != null && sqlSessionFactory != null) {
            SqlSessionUtils.closeSqlSession(sqlSession, sqlSessionFactory);
        }
    }

    @Override
    public ApiModuleDTO getNode(String id) {
        ApiModule module = apiModuleMapper.selectByPrimaryKey(id);
        if (module == null) {
            return null;
        }
        ApiModuleDTO dto = JSON.parseObject(JSON.toJSONString(module), ApiModuleDTO.class);
        return dto;
    }

    @Override
    public void updatePos(String id, Double pos) {
        extApiModuleMapper.updatePos(id, pos);
    }

    public void dragNode(DragModuleRequest request) {

        checkApiModuleExist(request);

        List<String> nodeIds = request.getNodeIds();

        List<ApiDefinitionResult> apiModule = queryByModuleIds(nodeIds);

        ApiModuleDTO nodeTree = request.getNodeTree();

        List<ApiModule> updateNodes = new ArrayList<>();
        if (nodeTree == null) {
            return;
        }
        buildUpdateDefinition(nodeTree, apiModule, updateNodes, "/", "0", 1);

        updateNodes = updateNodes.stream()
                .filter(item -> nodeIds.contains(item.getId()))
                .collect(Collectors.toList());

        batchUpdateModule(updateNodes);

        batchUpdateApiDefinition(apiModule);
    }

    private void buildUpdateDefinition(ApiModuleDTO rootNode, List<ApiDefinitionResult> apiDefinitions,
                                       List<ApiModule> updateNodes, String rootPath, String pId, int level) {
        rootPath = rootPath + rootNode.getName();

        if (level > 8) {
            MSException.throwException(Translator.get("node_deep_limit"));
        }
        if ("root".equals(rootNode.getId())) {
            rootPath = "";
        }
        ApiModule apiDefinitionNode = new ApiModule();
        apiDefinitionNode.setId(rootNode.getId());
        apiDefinitionNode.setLevel(level);
        apiDefinitionNode.setParentId(pId);
        updateNodes.add(apiDefinitionNode);

        for (ApiDefinitionResult item : apiDefinitions) {
            if (StringUtils.equals(item.getModuleId(), rootNode.getId())) {
                item.setModulePath(rootPath);
            }
        }

        List<ApiModuleDTO> children = rootNode.getChildren();
        if (children != null && children.size() > 0) {
            for (int i = 0; i < children.size(); i++) {
                buildUpdateDefinition(children.get(i), apiDefinitions, updateNodes, rootPath + '/', rootNode.getId(), level + 1);
            }
        }
    }

    private void batchUpdateModule(List<ApiModule> updateNodes) {
        SqlSession sqlSession = sqlSessionFactory.openSession(ExecutorType.BATCH);
        ApiModuleMapper apiModuleMapper = sqlSession.getMapper(ApiModuleMapper.class);
        updateNodes.forEach((value) -> {
            apiModuleMapper.updateByPrimaryKeySelective(value);
        });
        sqlSession.flushStatements();
        if (sqlSession != null && sqlSessionFactory != null) {
            SqlSessionUtils.closeSqlSession(sqlSession, sqlSessionFactory);
        }
    }

    public ApiModule getModuleByNameAndLevel(String projectId, String protocol, String name, Integer level) {
        ApiModuleExample example = new ApiModuleExample();
        example.createCriteria().andProjectIdEqualTo(projectId).andProtocolEqualTo(protocol).andNameEqualTo(name).andLevelEqualTo(level);
        List<ApiModule> modules = apiModuleMapper.selectByExample(example);
        if (CollectionUtils.isNotEmpty(modules)) {
            return modules.get(0);
        } else {
            return null;
        }
    }


    public List<ApiModule> getMListByProAndProtocol(String projectId, String protocol) {
        ApiModuleExample example = new ApiModuleExample();
        example.createCriteria().andProjectIdEqualTo(projectId).andProtocolEqualTo(protocol);
        return apiModuleMapper.selectByExample(example);
    }

    public String getLogDetails(List<String> ids) {
        ApiModuleExample example = new ApiModuleExample();
        ApiModuleExample.Criteria criteria = example.createCriteria();
        criteria.andIdIn(ids);
        List<ApiModule> nodes = apiModuleMapper.selectByExample(example);
        if (CollectionUtils.isNotEmpty(nodes)) {
            List<String> names = nodes.stream().map(ApiModule::getName).collect(Collectors.toList());
            OperatingLogDetails details = new OperatingLogDetails(JSON.toJSONString(ids), nodes.get(0).getProjectId(), String.join(",", names), nodes.get(0).getCreateUser(), new LinkedList<>());
            return JSON.toJSONString(details);
        }
        return null;
    }

    public String getLogDetails(ApiModule node) {
        ApiModule module = null;
        if (StringUtils.isNotEmpty(node.getId())) {
            module = apiModuleMapper.selectByPrimaryKey(node.getId());
        }
        if (module == null && StringUtils.isNotEmpty(node.getName())) {
            ApiModuleExample example = new ApiModuleExample();
            ApiModuleExample.Criteria criteria = example.createCriteria();
            criteria.andNameEqualTo(node.getName()).andProjectIdEqualTo(node.getProjectId());
            if (StringUtils.isNotEmpty(node.getProtocol())) {
                criteria.andProtocolEqualTo(node.getProtocol());
            }
            if (StringUtils.isNotEmpty(node.getParentId())) {
                criteria.andParentIdEqualTo(node.getParentId());
            } else {
                criteria.andParentIdIsNull();
            }
            if (StringUtils.isNotEmpty(node.getId())) {
                criteria.andIdNotEqualTo(node.getId());
            }
            List<ApiModule> list = apiModuleMapper.selectByExample(example);
            if (CollectionUtils.isNotEmpty(list)) {
                module = list.get(0);
            }
        }
        if (module != null) {
            List<DetailColumn> columns = ReflexObjectUtil.getColumns(module, ModuleReference.moduleColumns);
            OperatingLogDetails details = new OperatingLogDetails(JSON.toJSONString(module.getId()), module.getProjectId(), module.getCreateUser(), columns);
            return JSON.toJSONString(details);
        }
        return null;
    }

    public long countById(String nodeId) {
        ApiModuleExample example = new ApiModuleExample();
        example.createCriteria().andIdEqualTo(nodeId);
        return apiModuleMapper.countByExample(example);
    }

    public ApiModule getDefaultNode(String projectId, String protocol) {
        ApiModuleExample example = new ApiModuleExample();
        example.createCriteria().andProjectIdEqualTo(projectId).andProtocolEqualTo(protocol).andNameEqualTo("未规划接口").andParentIdIsNull();
        List<ApiModule> list = apiModuleMapper.selectByExample(example);
        if (CollectionUtils.isEmpty(list)) {
            ApiModule record = new ApiModule();
            record.setId(UUID.randomUUID().toString());
            record.setName("未规划接口");
            record.setProtocol(protocol);
            record.setPos(1.0);
            record.setLevel(1);
            record.setCreateTime(System.currentTimeMillis());
            record.setUpdateTime(System.currentTimeMillis());
            record.setProjectId(projectId);
            record.setCreateUser(SessionUtils.getUserId());
            apiModuleMapper.insert(record);
            return record;
        } else {
            return list.get(0);
        }
    }

    public ApiModule getDefaultNodeUnCreateNew(String projectId, String protocol) {
        ApiModuleExample example = new ApiModuleExample();
        example.createCriteria().andProjectIdEqualTo(projectId).andProtocolEqualTo(protocol).andNameEqualTo("未规划接口").andParentIdIsNull();
        List<ApiModule> list = apiModuleMapper.selectByExample(example);
        if (CollectionUtils.isEmpty(list)) {
            return null;
        } else {
            return list.get(0);
        }
    }

    public long countTrashApiData(String projectId, String protocol) {
        ApiDefinitionExample example = new ApiDefinitionExample();
        example.createCriteria().andProjectIdEqualTo(projectId).andProtocolEqualTo(protocol).andStatusEqualTo("Trash");
        return extApiDefinitionMapper.countByExample(example);
    }

    public String getModuleNameById(String moduleId) {
        return extApiModuleMapper.getNameById(moduleId);
    }

    /**
     * 返回数据库中存在的id
     *
     * @param protocalModuleIdMap <protocol , List<moduleId>>
     * @return
     */
    public Map<String, List<String>> checkModuleIds(Map<String, List<String>> protocalModuleIdMap) {
        Map<String, List<String>> returnMap = new HashMap<>();
        if (MapUtils.isNotEmpty(protocalModuleIdMap)) {
            ApiModuleExample example = new ApiModuleExample();
            for (Map.Entry<String, List<String>> entry : protocalModuleIdMap.entrySet()) {
                String protocol = entry.getKey();
                List<String> moduleIds = entry.getValue();
                if (CollectionUtils.isNotEmpty(moduleIds)) {
                    example.clear();
                    example.createCriteria().andIdIn(moduleIds).andProtocolEqualTo(protocol);
                    List<ApiModule> moduleList = apiModuleMapper.selectByExample(example);
                    if (CollectionUtils.isNotEmpty(moduleList)) {
                        List<String> idLIst = new ArrayList<>();
                        moduleList.forEach(module -> {
                            idLIst.add(module.getId());
                        });
                        returnMap.put(protocol, idLIst);
                    }

                }
            }
        }
        return returnMap;
    }

    /**
     * 上传文件时对文件的模块进行检测
     *
     * @param moduleId        上传文件时选的模块ID
     * @param projectId
     * @param protocol
     * @param data
     * @param fullCoverage    是否覆盖接口
     * @param fullCoverageApi 是否更新当前接口所在模块 (如果开启url重复，可重复的是某一模块下的接口，注：只在这个模块下，不包含其子模块)
     * @return Return to the newly added module list and api list
     */
    public UpdateApiModuleDTO checkApiModule(String moduleId, String projectId, String protocol, List<ApiDefinitionWithBLOBs> data, Boolean fullCoverage, Boolean fullCoverageApi, boolean urlRepeat) {
        Map<String, ApiModule> map = new HashMap<>();
        Map<String, ApiDefinitionWithBLOBs> updateApiMap = new HashMap<>();
        //获取当前项目的当前协议下的所有模块的Tree
        List<ApiModuleDTO> apiModules = this.getApiModulesByProjectAndPro(projectId, protocol);
        List<ApiModuleDTO> nodeTreeByProjectId = this.getNodeTrees(apiModules);

        Map<String, List<ApiModule>> pidChildrenMap = new HashMap<>();
        Map<String, String> idPathMap = new HashMap<>();
        Map<String, ApiModuleDTO> idModuleMap = apiModules.stream().collect(Collectors.toMap(ApiModuleDTO::getId, apiModuleDTO -> apiModuleDTO));
        buildProcessData(nodeTreeByProjectId, pidChildrenMap, idPathMap);

        Map<String, ApiDefinitionWithBLOBs> methodPathMap = data.stream().collect(Collectors.toMap(t -> t.getMethod() + t.getPath(), apiDefinition -> apiDefinition));

        //获取选中的模块
        ApiModuleDTO chooseModule = null;
        if (moduleId != null) {
            chooseModule = idModuleMap.get(moduleId);
        }
        List<ApiDefinitionWithBLOBs> repeatApiDefinitionWithBLOBs;

        if (chooseModule != null) {
            repeatApiDefinitionWithBLOBs = extApiDefinitionMapper.selectRepeatByBLOBsSameUrl(data, chooseModule.getId());
        } else {
            repeatApiDefinitionWithBLOBs = extApiDefinitionMapper.selectRepeatByBLOBs(data);
        }

        //允许接口重复
        if (urlRepeat) {
            //允许覆盖接口
            if (fullCoverage) {
                //允许覆盖模块
                if (fullCoverageApi) {
                    coverApiModule(map, updateApiMap, pidChildrenMap, idPathMap, idModuleMap, methodPathMap, chooseModule, repeatApiDefinitionWithBLOBs);
                } else {
                    //覆盖但不覆盖模块
                    justCoverApi(map, updateApiMap, pidChildrenMap, idPathMap, idModuleMap, methodPathMap, chooseModule, repeatApiDefinitionWithBLOBs);
                }
            } else {
                //不覆盖接口，直接新增
                setModule(map, pidChildrenMap, idPathMap, idModuleMap, methodPathMap, chooseModule);
            }
        } else {
            //不允许接口重复
            if (fullCoverage) {
                if (fullCoverageApi) {
                    coverApiModule(map, updateApiMap, pidChildrenMap, idPathMap, idModuleMap, methodPathMap, chooseModule, repeatApiDefinitionWithBLOBs);
                } else {
                    //覆盖但不覆盖模块
                    justCoverApi(map, updateApiMap, pidChildrenMap, idPathMap, idModuleMap, methodPathMap, chooseModule, repeatApiDefinitionWithBLOBs);
                }

            } else {
                //不覆盖接口
                if (!repeatApiDefinitionWithBLOBs.isEmpty()) {
                    Map<String, ApiDefinitionWithBLOBs> collect = repeatApiDefinitionWithBLOBs.stream().collect(Collectors.toMap(t -> t.getMethod() + t.getPath(), apiDefinition -> apiDefinition));
                    collect.forEach((k, v) -> {
                        if (methodPathMap.get(k) != null) {
                            methodPathMap.remove(k);
                        }
                    });
                }
                setModule(map, pidChildrenMap, idPathMap, idModuleMap, methodPathMap, chooseModule);
            }
        }

        UpdateApiModuleDTO updateApiModuleDTO = new UpdateApiModuleDTO();
        updateApiModuleDTO.setModuleList((List<ApiModule>) map.values());
        updateApiModuleDTO.setApiDefinitionWithBLOBsList((List<ApiDefinitionWithBLOBs>) updateApiMap.values());
        return updateApiModuleDTO;
    }

    private void coverApiModule(Map<String, ApiModule> map, Map<String, ApiDefinitionWithBLOBs> updateApiMap, Map<String, List<ApiModule>> pidChildrenMap, Map<String, String> idPathMap, Map<String, ApiModuleDTO> idModuleMap, Map<String, ApiDefinitionWithBLOBs> methodPathMap, ApiModuleDTO chooseModule, List<ApiDefinitionWithBLOBs> repeatApiDefinitionWithBLOBs) {
        if (!repeatApiDefinitionWithBLOBs.isEmpty()) {
            Map<String, ApiDefinitionWithBLOBs> collect = repeatApiDefinitionWithBLOBs.stream().collect(Collectors.toMap(t -> t.getMethod() + t.getPath(), apiDefinition -> apiDefinition));
            collect.forEach((k, v) -> {
                ApiDefinitionWithBLOBs apiDefinitionWithBLOBs = methodPathMap.get(k);
                if (apiDefinitionWithBLOBs != null) {
                    //Check whether the content has changed, if not, do not change the creatoCover
                    Boolean toCover = apiDefinitionService.checkIsSynchronize(v, apiDefinitionWithBLOBs);
                    //需要更新
                    if (toCover) {
                        if (updateApiMap.get(k) != null) {
                            apiDefinitionWithBLOBs.setId(v.getId());
                            updateApiMap.put(k, apiDefinitionWithBLOBs);
                        }
                    } else {
                        methodPathMap.remove(k);
                    }
                }
            });
        }
        setModule(map, pidChildrenMap, idPathMap, idModuleMap, methodPathMap, chooseModule);
    }

    private void justCoverApi(Map<String, ApiModule> map, Map<String, ApiDefinitionWithBLOBs> updateApiMap, Map<String, List<ApiModule>> pidChildrenMap, Map<String, String> idPathMap, Map<String, ApiModuleDTO> idModuleMap, Map<String, ApiDefinitionWithBLOBs> methodPathMap, ApiModuleDTO chooseModule, List<ApiDefinitionWithBLOBs> repeatApiDefinitionWithBLOBs) {
        if (!repeatApiDefinitionWithBLOBs.isEmpty()) {
            Map<String, ApiDefinitionWithBLOBs> collect = repeatApiDefinitionWithBLOBs.stream().collect(Collectors.toMap(t -> t.getMethod() + t.getPath(), apiDefinition -> apiDefinition));
            collect.forEach((k, v) -> {
                ApiDefinitionWithBLOBs apiDefinitionWithBLOBs = methodPathMap.get(k);
                if (apiDefinitionWithBLOBs != null) {
                    //Check whether the content has changed, if not, do not change the creatoCover
                    Boolean toCover = apiDefinitionService.checkIsSynchronize(v, apiDefinitionWithBLOBs);
                    //需要更新
                    if (toCover) {
                        if (updateApiMap.get(k) != null) {
                            apiDefinitionWithBLOBs.setId(v.getId());
                            updateApiMap.put(k, apiDefinitionWithBLOBs);
                        }
                    }
                    methodPathMap.remove(k);
                }
            });
        }
        setModule(map, pidChildrenMap, idPathMap, idModuleMap, methodPathMap, chooseModule);
    }

    private void setModule(Map<String, ApiModule> map, Map<String, List<ApiModule>> pidChildrenMap, Map<String, String> idPathMap, Map<String, ApiModuleDTO> idModuleMap, Map<String, ApiDefinitionWithBLOBs> methodPathMap, ApiModuleDTO chooseModule) {
        methodPathMap.forEach((methodPath, datum) -> {
            String[] pathTree;
            String modulePath = datum.getModulePath();
            ApiModule apiModule = map.get(modulePath);
            if (chooseModule != null) {
                if (chooseModule.getParentId() == null) {
                    chooseModule.setParentId("root");
                }
                String chooseModuleParentId = chooseModule.getParentId();
                //导入时选了模块，且接口有模块的
                if (StringUtils.isNotBlank(modulePath)) {
                    List<ApiModule> moduleList = pidChildrenMap.get(chooseModuleParentId);
                    String s;
                    if (chooseModuleParentId.equals("root")) {
                        s = "/" + chooseModule.getName();
                    } else {
                        s = idPathMap.get(chooseModuleParentId);
                    }
                    pathTree = getPathTree(s + modulePath);

                    ApiModule chooseModuleOne = JSON.parseObject(JSON.toJSONString(chooseModule), ApiModule.class);
                    ApiModule minModule = getMinModule(pathTree, moduleList, chooseModuleOne, pidChildrenMap, map, idPathMap, idModuleMap);
                    String id = minModule.getId();
                    datum.setModuleId(id);
                    datum.setModulePath(idPathMap.get(id));
                } else {
                    //导入时选了模块，且接口没有模块的
                    datum.setModuleId(chooseModule.getId());
                    datum.setModulePath(idPathMap.get(chooseModule.getId()));
                }
            } else {
                if (StringUtils.isNotBlank(modulePath)) {
                    //导入时没选模块但接口有模块的，根据modulePath，和当前协议查询当前项目里是否有同名称模块，如果有，就在该模块下建立接口，否则新建模块
                    pathTree = getPathTree(modulePath);
                    if (apiModule != null) {
                        datum.setModuleId(apiModule.getId());
                        datum.setModulePath(modulePath);
                    } else {
                        List<ApiModule> moduleList = pidChildrenMap.get("root");
                        ApiModule minModule = getMinModule(pathTree, moduleList, null, pidChildrenMap, map, idPathMap, idModuleMap);
                        String id = minModule.getId();
                        datum.setModuleId(id);
                        datum.setModulePath(idPathMap.get(id));
                    }
                } else {
                    //导入时即没选中模块，接口自身也没模块的，直接返会当前项目，当前协议下的默认模块
                    List<ApiModule> moduleList = pidChildrenMap.get("root");
                    for (ApiModule module : moduleList) {
                        if (module.getName().equals("未规划接口")) {
                            datum.setModuleId(module.getId());
                            datum.setModulePath("/" + module.getName());
                        }
                    }
                }
            }
        });
    }

    private String[] getPathTree(String modulePath) {
        String substring = modulePath.substring(0, 1);
        if (substring.equals("/")) {
            modulePath = modulePath.substring(1);
        }
        if (modulePath.contains("/")) {
            //如果模块有层级，逐级查找，如果某一级不在当前项目了，则新建该层级的模块及其子集
            return modulePath.split("/");
        } else {
            return new String[]{modulePath};
        }
    }

    private ApiModule getMinModule(String[] tagTree, List<ApiModule> moduleList, ApiModule parentModule, Map<String, List<ApiModule>> pidChildrenMap, Map<String, ApiModule> map, Map<String, String> idPathMap, Map<String, ApiModuleDTO> idModuleMap) {
        //如果parentModule==null 则证明需要创建根目录同级的模块
        ApiModule returnModule = null;
        for (int i = 0; i < tagTree.length; i++) {
            int finalI = i;
            List<ApiModule> collect = moduleList.stream().filter(t -> t.getName().equals(tagTree[finalI])).collect(Collectors.toList());
            if (collect.isEmpty()) {
                if (parentModule == null) {
                    List<ApiModule> moduleList1 = pidChildrenMap.get("root");
                    ApiModule apiModule = moduleList1.get(0);
                    apiModule.setId("root");
                    apiModule.setLevel(0);
                    parentModule = apiModule;
                } else if (i > 0) {
                    if (!moduleList.isEmpty()) {
                        String parentId = moduleList.get(0).getParentId();
                        ApiModuleDTO apiModuleDTO = idModuleMap.get(parentId);
                        parentModule = JSON.parseObject(JSON.toJSONString(apiModuleDTO), ApiModule.class);
                    }
                }
                return createModule(tagTree, i, parentModule, map, pidChildrenMap, idPathMap);
            } else {
                returnModule = collect.get(0);
                moduleList = pidChildrenMap.get(collect.get(0).getId());
            }
        }
        return returnModule;
    }


    private ApiModule createModule(String[] tagTree, int i, ApiModule parentModule, Map<String, ApiModule> map, Map<String, List<ApiModule>> pidChildrenMap, Map<String, String> idPathMap) {
        ApiModule returnModule = null;
        for (int i1 = i; i1 < tagTree.length; i1++) {
            String pathName = tagTree[i1];
            ApiModule newModule = this.getNewModule(pathName, parentModule.getProjectId(), parentModule.getLevel() + 1);
            String parentId;
            if (parentModule.getId().equals("root")) {
                parentId = null;
            } else {
                parentId = parentModule.getId();
            }
            double pos = this.getNextLevelPos(parentModule.getProjectId(), parentModule.getLevel() + 1, parentId);
            newModule.setPos(pos);
            newModule.setProtocol(parentModule.getProtocol());
            newModule.setParentId(parentId);
            List<ApiModule> moduleList = pidChildrenMap.get(parentModule.getId());
            if (moduleList != null) {
                moduleList.add(newModule);
            } else {
                moduleList = new ArrayList<>();
                moduleList.add(newModule);
                pidChildrenMap.put(parentModule.getId(), moduleList);
            }

            String parentPath = idPathMap.get(parentModule.getId());
            String path;
            if (StringUtils.isNotBlank(parentPath)) {
                path = parentPath + "/" + pathName;
            } else {
                path = "/" + pathName;
            }
            idPathMap.put(newModule.getId(), path);
            map.putIfAbsent(path, newModule);
            parentModule = newModule;
            returnModule = newModule;
        }
        return returnModule;
    }

    private void buildProcessData(List<ApiModuleDTO> nodeTreeByProjectId, Map<String, List<ApiModule>> pidChildrenMap, Map<String, String> idPathMap) {
        List<ApiModuleDTO> childrenList = new ArrayList<>();
        int i = 0;
        List<ApiModule> moduleList = new ArrayList<>();
        for (ApiModuleDTO apiModuleDTO : nodeTreeByProjectId) {
            if (apiModuleDTO.getPath() != null) {
                apiModuleDTO.setPath(apiModuleDTO.getPath() + "/" + apiModuleDTO.getName());
            } else {
                apiModuleDTO.setPath("/" + apiModuleDTO.getName());
            }
            idPathMap.put(apiModuleDTO.getId(), apiModuleDTO.getPath());
            if (StringUtils.isBlank(apiModuleDTO.getParentId())) {
                apiModuleDTO.setParentId("root");
            }
            ApiModule apiModule = buildModule(moduleList, apiModuleDTO);
            if (pidChildrenMap.get(apiModuleDTO.getParentId()) != null) {
                pidChildrenMap.get(apiModuleDTO.getParentId()).add(apiModule);
            } else {
                pidChildrenMap.put(apiModuleDTO.getParentId(), moduleList);
            }
            i = i + 1;
            if (apiModuleDTO.getChildren() != null) {
                childrenList.addAll(apiModuleDTO.getChildren());
            } else {
                childrenList.addAll(new ArrayList<>());
                if (i == nodeTreeByProjectId.size() && childrenList.size() == 0) {
                    pidChildrenMap.put(apiModuleDTO.getId(), new ArrayList<>());
                }
            }
        }
        if (i == nodeTreeByProjectId.size() && nodeTreeByProjectId.size() > 0) {
            buildProcessData(childrenList, pidChildrenMap, idPathMap);
        }
    }

    private ApiModule buildModule(List<ApiModule> moduleList, ApiModuleDTO apiModuleDTO) {
        ApiModule apiModule = new ApiModule();
        apiModule.setId(apiModuleDTO.getId());
        apiModule.setName(apiModuleDTO.getName());
        apiModule.setParentId(apiModuleDTO.getParentId());
        apiModule.setProjectId(apiModuleDTO.getProjectId());
        apiModule.setProtocol(apiModuleDTO.getProtocol());
        apiModule.setLevel(apiModuleDTO.getLevel());
        moduleList.add(apiModule);
        return apiModule;
    }


}
