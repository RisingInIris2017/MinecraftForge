/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.minecraftforge.debug.misc;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

import org.slf4j.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.data.DataGenerator;
import net.minecraft.data.DataProvider;
import net.minecraft.data.HashCache;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.tags.TagKey;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TagsUpdatedEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.util.thread.EffectiveSide;
import net.minecraftforge.forge.event.lifecycle.GatherDataEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistryEntry;
import net.minecraftforge.registries.RegistryBuilder;
import net.minecraftforge.registries.RegistryObject;

/**
 * <p> 该测试类展示了一个注册同步或未同步的数据包注册项的例子，
 * 以及如何使用一个 DataProvider 来生成它们所需的 JSON 文件。
 * 同时也完成了对数据和标签是否正确加载和同步的校验。
 * 数据是从下列测试资源位置载入的：</p>
 * <ul>
 * <li><code>data/data_pack_registries_test/data_pack_registries_test/unsyncable/test.json</code></li>
 * <li><code>data/data_pack_registries_test/tags/data_pack_registries_test/unsyncable/test.json</code></li>
 * <li><code>data/data_pack_registries_test/data_pack_registries_test/syncable/test.json</code></li>
 * <li><code>data/data_pack_registries_test/tags/data_pack_registries_test/syncable/test.json</code></li>
 * </ul>
 */
@Mod(DataPackRegistriesTest.MODID)
public class DataPackRegistriesTest
{
    private static final boolean ENABLED = true;
    public static final String MODID = "data_pack_registries_test";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation TEST_RL = new ResourceLocation(MODID, "test");
    
    private final RegistryObject<Unsyncable> datagenTestObject;

    public DataPackRegistriesTest()
    {
        if (!ENABLED)
            return;

        final IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        final IEventBus forgeBus = MinecraftForge.EVENT_BUS;
        
        // 延迟注册器 (Deferred Registers) 可以在静态初始化或模组构造函数中创建，用于注册数据包注册项。
        // （考虑到做是否启用的检查时，模组构造函数的写法比静态初始化的写法简单，我们采用前者。)
        // 借助静态注册项，任何模组都可以给某个给定的数据包注册项作延迟注册（Deferred Register），
        // 但只有一个模组可以使用 makeRegistry 方法注册内部注册项。
        final DeferredRegister<Unsyncable> unsyncables = DeferredRegister.create(Unsyncable.REGISTRY_KEY, MODID);
        final DeferredRegister<Syncable> syncables = DeferredRegister.create(Syncable.REGISTRY_KEY, MODID);
        
        // RegistryBuilder#dataPackRegistry 标志着该注册项是一个数据包注册项，而非一个静态注册项。
        unsyncables.makeRegistry(Unsyncable.class,
            () -> new RegistryBuilder<Unsyncable>().disableSaving().dataPackRegistry(Unsyncable.DIRECT_CODEC));
        // #dataPackRegistry 被重载为需要第二个编解码器参数的形式，这标志着数据包注册表是可同步的。
        syncables.makeRegistry(Syncable.class,
            () -> new RegistryBuilder<Syncable>().disableSaving().dataPackRegistry(Syncable.DIRECT_CODEC, Syncable.DIRECT_CODEC));
        
        // 数据包注册项的元素可以由 datagen 方式生成，但它们必须先被注册为内置对象。
        this.datagenTestObject = unsyncables.register("datagen_test", () -> new Unsyncable("Datagen Success"));
        
        unsyncables.register(modBus);
        syncables.register(modBus);
        
        modBus.addListener(this::onGatherData);
        forgeBus.addListener(this::onServerStarting);
        
        if (FMLEnvironment.dist == Dist.CLIENT)
        {
            ClientEvents.subscribeClientEvents();
        }
    }
    
    private void onGatherData(final GatherDataEvent event)
    {
        if (!event.includeServer())
            return;
        // 使用 datagen 生成数据包注册项对象的示例。
        // 要被 datagen 生成的对象，必须先被注册，例如可以通过上面的 DeferredRegister
        // 这会输出到 data/data_pack_registries_test/data_pack_registries_test/unsyncable/datagen_test.json 文件中。
        final DataGenerator generator = event.getGenerator();
        final Path outputFolder = generator.getOutputFolder();
        final RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, RegistryAccess.BUILTIN.get());
        final Gson gson = new GsonBuilder().setPrettyPrinting().create();
        final ResourceLocation registryId = Unsyncable.REGISTRY_KEY.location();
        final ResourceLocation id = this.datagenTestObject.getId();
        final Unsyncable element = this.datagenTestObject.get();
        final String pathString = String.join("/", PackType.SERVER_DATA.getDirectory(), id.getNamespace(), registryId.getNamespace(), registryId.getPath(), id.getPath()+".json");
        final Path path = outputFolder.resolve(pathString);
        generator.addProvider(new DataProvider()
        {
            @Override
            public void run(final HashCache cache) throws IOException
            {
                Unsyncable.DIRECT_CODEC.encodeStart(ops, element)
                    .resultOrPartial(msg -> LOGGER.error("Failed to encode {}: {}", path, msg)) // Log error on encode failure.
                    .ifPresent(json -> // Output to file on encode success.
                    {
                        try
                        {
                            DataProvider.save(gson, cache, json, path);
                        }
                        catch (IOException e) // The throws can't deal with this exception, because we're inside the ifPresent.
                        {
                            LOGGER.error("Failed to save " + pathString, e);
                        }
                    });
            }

            @Override
            public String getName()
            {
                return String.format("%s provider for %s", registryId, MODID);
            }
        });
    }
    
    private void onServerStarting(final ServerStartingEvent event)
    {
        // 断言 JSON 对象和标签存在。
        final RegistryAccess registries = event.getServer().registryAccess();
        final Registry<Unsyncable> registry = registries.registryOrThrow(Unsyncable.REGISTRY_KEY);
        final ResourceKey<Unsyncable> key = ResourceKey.create(Unsyncable.REGISTRY_KEY, TEST_RL);
        final Holder<Unsyncable> holder = registry.getHolderOrThrow(key);
        final Unsyncable testObject = registry.get(TEST_RL);
        if (!testObject.value().equals("success"))
            throw new IllegalStateException("Incorrect value loaded: " + testObject.value());
        final TagKey<Unsyncable> tag = TagKey.create(Unsyncable.REGISTRY_KEY, TEST_RL);
        if (!registry.getTag(tag).get().contains(holder))
            throw new IllegalStateException(String.format(Locale.ENGLISH, "Tag %s does not contain %s", tag, TEST_RL));
        
        LOGGER.info("DataPackRegistriesTest server data loaded successfully!");
    }
    
    public static class ClientEvents
    {
        private static void subscribeClientEvents()
        {
            MinecraftForge.EVENT_BUS.addListener(ClientEvents::onClientTagsUpdated);
        }
        
        private static void onClientTagsUpdated(final TagsUpdatedEvent event)
        {
            // 我们希望检查标签是否已经在玩家登录后同步。
            // 标签在登录流程的末尾才同步，而许多有关的事件则在标签同步之前触发。
            // TagsUpdatedEvent 事件有正确的触发时机，但它同时在服务线程 (server) 和渲染线程 (render thread) 触发，
            // 所以我们需要确保我们处在渲染线程。
            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null || EffectiveSide.get().isServer())
                return;

            // 断言被同步的对象和标签存在。TagsUpdatedEvent 事件有专属的 RegistryAccess,
            // 但我们应当检查玩家的连接的 RegistryAccess，因为它是客户端保留的拷贝在整个游戏运行期间所存放的位置，
            // 和大多数情况下模组查询客户端时应当查询的位置。
            RegistryAccess registries = player.connection.registryAccess();
            final Registry<Syncable> registry = registries.registryOrThrow(Syncable.REGISTRY_KEY);
            final ResourceKey<Syncable> key = ResourceKey.create(Syncable.REGISTRY_KEY, TEST_RL);
            final Holder<Syncable> holder = registry.getHolderOrThrow(key);
            final Syncable testObject = registry.get(TEST_RL);
            if (!testObject.value().equals("success"))
                throw new IllegalStateException("Incorrect value synced: " + testObject.value());
            final TagKey<Syncable> tag = TagKey.create(Syncable.REGISTRY_KEY, TEST_RL);
            if (!registry.getTag(tag).get().contains(holder))
                throw new IllegalStateException(String.format(Locale.ENGLISH, "Tag %s does not contain %s", tag, TEST_RL));
            
            LOGGER.info("DataPackRegistriesTest client data synced successfully!");
        }
    }

    public static class Unsyncable extends ForgeRegistryEntry<Unsyncable>
    {
        public static final ResourceKey<Registry<Unsyncable>> REGISTRY_KEY = ResourceKey.createRegistryKey(new ResourceLocation(MODID, "unsyncable"));
        public static final Codec<Unsyncable> DIRECT_CODEC = Codec.STRING.fieldOf("value").codec().xmap(Unsyncable::new, Unsyncable::value);

        private final String value;

        public Unsyncable(final String stringValue)
        {
            this.value = stringValue;
        }

        public String value()
        {
            return this.value;
        }
    }
    
    public static class Syncable extends ForgeRegistryEntry<Syncable>
    {
        public static final ResourceKey<Registry<Syncable>> REGISTRY_KEY = ResourceKey.createRegistryKey(new ResourceLocation(MODID, "syncable"));
        public static final Codec<Syncable> DIRECT_CODEC = Codec.STRING.fieldOf("value").codec().xmap(Syncable::new, Syncable::value);
        
        private final String value;
        
        public Syncable(final String value)
        {
            this.value = value;
        }
        
        public String value()
        {
            return this.value;
        }
    }
}
